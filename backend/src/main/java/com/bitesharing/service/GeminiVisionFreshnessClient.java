package com.bitesharing.service;

import com.bitesharing.dto.FoodFreshnessResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calls Google Gemini vision for food freshness. Used by {@code freshness.mode=GEMINI},
 * {@code HYBRID}, {@code PYTHON_OR_GEMINI}, and {@code GEMINI_OR_PYTHON}.
 * Engine choice stays server-side; clients only see {@code freshness.client-model-label}.
 */
@Service
@Slf4j
public class GeminiVisionFreshnessClient {

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[^{}]*(?:\\{[^{}]*}[^{}]*)*}");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final boolean useJsonMimeType;

    public GeminiVisionFreshnessClient(
            @Qualifier("freshnessRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${gemini.api.key:}") String apiKey,
            @Value("${gemini.model:gemini-1.5-pro}") String model,
            @Value("${gemini.temperature:0.12}") double temperature,
            @Value("${gemini.freshness.use-json-mode:true}") boolean useJsonMimeType) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "gemini-1.5-pro" : model.trim();
        this.temperature = temperature;
        this.useJsonMimeType = useJsonMimeType;
    }

    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    /**
     * Full Gemini vision pass: structured JSON when possible, calibrated confidence and expiry rules.
     */
    public FoodFreshnessResult analyze(byte[] imageBytes, String filename) {
        long start = System.currentTimeMillis();
        if (!isConfigured()) {
            return unknown(start, "GEMINI", "Gemini API key not configured.");
        }
        try {
            String mime = inferImageMimeType(filename, imageBytes);
            String imageData = Base64.getEncoder().encodeToString(imageBytes);
            return callGeminiStructured(imageData, mime, start);
        } catch (RestClientException e) {
            log.warn("Gemini request failed: {}", e.getMessage());
            return unknown(start, "GEMINI", "Gemini request failed: " + e.getMessage());
        } catch (Exception e) {
            log.warn("Gemini processing error: {}", e.getMessage(), e);
            return unknown(start, "GEMINI", "Gemini processing failed: " + e.getMessage());
        }
    }

    private FoodFreshnessResult callGeminiStructured(String imageData, String mime, long start) throws Exception {
        String url = endpointUrl();
        String raw;
        try {
            raw = postGenerateContent(url, buildRequestBody(imageData, mime, useJsonMimeType));
        } catch (HttpStatusCodeException e) {
            if (useJsonMimeType && e.getStatusCode().value() == 400) {
                log.warn("Gemini rejected JSON schema mode ({}); retrying without responseSchema.", e.getMessage());
                raw = postGenerateContent(url, buildRequestBody(imageData, mime, false));
            } else {
                throw e;
            }
        }
        if (raw == null) {
            return unknown(start, "GEMINI", "Empty response from Gemini.");
        }
        JsonNode root = objectMapper.readTree(raw);
        if (root.has("promptFeedback")) {
            JsonNode pf = root.get("promptFeedback");
            if (pf != null && pf.has("blockReason")) {
                log.warn("Gemini blocked prompt: {}", pf.get("blockReason"));
                return unknown(start, "GEMINI", "Content blocked by safety filters.");
            }
        }
        String text = extractModelText(root);
        if (text == null || text.isBlank()) {
            log.warn("Gemini empty text; body snippet: {}", raw.length() > 400 ? raw.substring(0, 400) : raw);
            return unknown(start, "GEMINI", "Gemini returned no text.");
        }
        ParsedJson parsed = parseFreshnessJson(text);
        if (parsed == null && useJsonMimeType) {
            String raw2 = postGenerateContent(url, buildRequestBody(imageData, mime, false));
            if (raw2 != null) {
                String text2 = extractModelText(objectMapper.readTree(raw2));
                parsed = text2 != null ? parseFreshnessJson(text2) : null;
            }
        }
        if (parsed == null) {
            log.warn("Could not parse Gemini JSON from: {}", text.length() > 200 ? text.substring(0, 200) : text);
            return unknown(start, "GEMINI", "Could not parse Gemini JSON output.");
        }

        String status = parsed.status();
        double confidence = clamp01(parsed.confidence());
        String reason = parsed.reason() == null || parsed.reason().isBlank()
                ? "Gemini vision assessment."
                : parsed.reason().trim();

        Map<String, Double> probs = new HashMap<>();
        if ("FRESH".equals(status)) {
            probs.put("fresh", confidence);
            probs.put("rotten", Math.max(0.0, 1.0 - confidence));
        } else {
            probs.put("rotten", confidence);
            probs.put("fresh", Math.max(0.0, 1.0 - confidence));
        }
        int predIdx = "FRESH".equals(status) ? 0 : 1;
        Integer expiry;
        String message;
        if ("ROTTEN".equals(status)) {
            expiry = 0;
            message = reason;
        } else if (confidence < 0.58) {
            expiry = 0;
            message = "Uncertain freshness — " + reason;
        } else {
            expiry = 4;
            message = reason;
        }

        return FoodFreshnessResult.builder()
                .status(status)
                .confidence(confidence)
                .modelUsed("GEMINI")
                .processingTimeMs(System.currentTimeMillis() - start)
                .message(message)
                .estimatedExpiryHours(expiry)
                .classProbabilities(probs)
                .predictedClassIndex(predIdx)
                .build();
    }

    private String postGenerateContent(String url, Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            log.warn("Gemini HTTP {}", response.getStatusCode());
            return null;
        }
        return response.getBody();
    }

    private Map<String, Object> buildRequestBody(String imageData, String mime, boolean jsonMode) {
        Map<String, Object> systemInstruction = Map.of(
                "parts",
                List.of(Map.of("text", buildSystemPrompt()))
        );
        Map<String, Object> userPartText = Map.of("text", buildUserPrompt(jsonMode));
        Map<String, Object> userPartImage = Map.of(
                "inlineData",
                Map.of("mimeType", mime, "data", imageData)
        );
        Map<String, Object> contents = Map.of(
                "parts",
                List.of(userPartText, userPartImage)
        );

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", temperature);
        generationConfig.put("topP", 0.9);
        if (jsonMode) {
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("responseSchema", buildFreshnessResponseSchema());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", systemInstruction);
        body.put("contents", List.of(contents));
        body.put("generationConfig", generationConfig);
        return body;
    }

    /**
     * Gemini REST Schema subset (see Google AI structured output docs).
     */
    private Map<String, Object> buildFreshnessResponseSchema() {
        Map<String, Object> statusProp = new LinkedHashMap<>();
        statusProp.put("type", "STRING");
        statusProp.put("enum", List.of("FRESH", "ROTTEN"));

        Map<String, Object> confidenceProp = Map.of("type", "NUMBER");
        Map<String, Object> reasonProp = Map.of("type", "STRING");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("status", statusProp);
        properties.put("confidence", confidenceProp);
        properties.put("reason", reasonProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "OBJECT");
        schema.put("properties", properties);
        schema.put("required", List.of("status", "confidence", "reason"));
        return schema;
    }

    private String buildSystemPrompt() {
        return """
                You assess visible food quality in photos for a surplus-food donation app (ShareBite).
                Use only what is visible in the image. Prefer ROTTEN when there is clear spoilage: mold, heavy \
                discoloration suggesting decay, visible pests, obvious slime, or food that appears clearly inedible.
                Prefer FRESH for normal surplus meals, intact produce, or packaged food that looks acceptable.
                If the image is not food, is too dark/blurry to judge, or is ambiguous, respond FRESH with \
                confidence below 0.5 and explain briefly in reason.
                Output must follow the response schema exactly (no extra keys).""";
    }

    private String buildUserPrompt(boolean schemaBacked) {
        if (schemaBacked) {
            return "Classify this image for donation routing. Return JSON only per schema.";
        }
        return """
                Classify this food image for donation safety. Output exactly one JSON object with keys:
                "status" (either FRESH or ROTTEN),
                "confidence" (number from 0 to 1),
                "reason" (one short factual sentence).
                No markdown fences, no text before or after the JSON.""";
    }

    private String endpointUrl() {
        String m = model.startsWith("models/") ? model.substring("models/".length()) : model;
        return String.format(
                "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s",
                m,
                apiKey);
    }

    private static String inferImageMimeType(String filename, byte[] bytes) {
        if (bytes != null && bytes.length >= 8) {
            if (bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
                return "image/png";
            }
            if (bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff) {
                return "image/jpeg";
            }
            if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[8] == 'W'
                    && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
                return "image/webp";
            }
        }
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/jpeg";
    }

    private String extractModelText(JsonNode root) {
        if (root == null || !root.has("candidates") || !root.get("candidates").isArray()) {
            return null;
        }
        JsonNode candidates = root.get("candidates");
        if (candidates.isEmpty()) {
            return null;
        }
        JsonNode candidate = candidates.get(0);
        if (candidate != null && candidate.has("finishReason")) {
            String fr = candidate.get("finishReason").asText("");
            if ("SAFETY".equalsIgnoreCase(fr) || "BLOCKLIST".equalsIgnoreCase(fr)) {
                log.warn("Gemini finishReason={}", fr);
                return null;
            }
        }
        if (candidate == null || !candidate.has("content")) {
            return null;
        }
        JsonNode content = candidate.get("content");
        if (!content.has("parts") || !content.get("parts").isArray()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : content.get("parts")) {
            if (part.has("text")) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(part.get("text").asText());
            }
        }
        return sb.length() == 0 ? null : sb.toString().trim();
    }

    private ParsedJson parseFreshnessJson(String raw) {
        String s = stripCodeFence(raw).trim();
        try {
            JsonNode node = objectMapper.readTree(s);
            if (node.has("status") && node.has("confidence")) {
                String status = node.get("status").asText("").trim().toUpperCase(Locale.ROOT);
                if (!"FRESH".equals(status) && !"ROTTEN".equals(status)) {
                    return null;
                }
                double conf = node.get("confidence").asDouble(Double.NaN);
                if (Double.isNaN(conf)) {
                    return null;
                }
                String reason = node.has("reason") ? node.get("reason").asText("") : "";
                return new ParsedJson(status, conf, reason);
            }
        } catch (Exception ignored) {
            // try regex extract
        }
        Matcher m = JSON_OBJECT.matcher(s);
        if (m.find()) {
            try {
                JsonNode node = objectMapper.readTree(m.group());
                if (node.has("status") && node.has("confidence")) {
                    String status = node.get("status").asText("").trim().toUpperCase(Locale.ROOT);
                    if (!"FRESH".equals(status) && !"ROTTEN".equals(status)) {
                        return null;
                    }
                    double conf = node.get("confidence").asDouble(Double.NaN);
                    String reason = node.has("reason") ? node.get("reason").asText("") : "";
                    return new ParsedJson(status, conf, reason);
                }
            } catch (Exception ignored2) {
                return null;
            }
        }
        return null;
    }

    private static String stripCodeFence(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                t = t.substring(nl + 1);
            }
            int end = t.lastIndexOf("```");
            if (end > 0) {
                t = t.substring(0, end).trim();
            }
        }
        return t;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || v < 0) {
            return 0.0;
        }
        return Math.min(1.0, v);
    }

    private static FoodFreshnessResult unknown(long start, String modelUsed, String message) {
        return FoodFreshnessResult.builder()
                .status("UNKNOWN")
                .confidence(0.0)
                .modelUsed(modelUsed)
                .processingTimeMs(System.currentTimeMillis() - start)
                .message(message)
                .estimatedExpiryHours(0)
                .classProbabilities(Map.of())
                .predictedClassIndex(null)
                .build();
    }

    private record ParsedJson(String status, double confidence, String reason) {
    }

    /**
     * Used by hybrid merge to run a secondary Gemini check with the same client behavior.
     */
    public FoodFreshnessResult analyzeForHybrid(byte[] imageBytes, String filename) {
        FoodFreshnessResult r = analyze(imageBytes, filename);
        if (r != null && r.getModelUsed() != null && "GEMINI".equals(r.getModelUsed())) {
            return FoodFreshnessResult.builder()
                    .status(r.getStatus())
                    .confidence(r.getConfidence())
                    .modelUsed("internal-gemini")
                    .processingTimeMs(r.getProcessingTimeMs())
                    .message(r.getMessage())
                    .estimatedExpiryHours(r.getEstimatedExpiryHours())
                    .classProbabilities(r.getClassProbabilities())
                    .predictedClassIndex(r.getPredictedClassIndex())
                    .build();
        }
        return r;
    }
}
