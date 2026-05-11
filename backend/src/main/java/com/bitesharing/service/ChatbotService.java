package com.bitesharing.service;

import com.bitesharing.dto.chatbot.ChatbotMatchDto;
import com.bitesharing.dto.chatbot.ChatbotMessageResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ShareBite help assistant: open-source style **FAQ retrieval** from bundled JSON
 * (no vendor lock-in). Optionally augments with **Ollama** (OSS local LLM) when enabled.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatbotService {

    private final ObjectMapper objectMapper;

    private KnowledgeRoot knowledge;

    @Value("${chatbot.ollama.enabled:false}")
    private boolean ollamaEnabled;

    @Value("${chatbot.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${chatbot.ollama.model:llama3.2}")
    private String ollamaModel;

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "must", "shall", "can", "need", "how", "what",
            "when", "where", "why", "who", "which", "this", "that", "these", "those",
            "to", "of", "in", "for", "on", "with", "at", "by", "from", "as", "into",
            "through", "before", "after", "above", "below", "up", "down",
            "out", "off", "over", "under", "again", "further", "then", "once", "here",
            "there", "all", "both", "each", "few", "more", "most", "other", "some", "such",
            "no", "nor", "not", "only", "own", "same", "so", "than", "too", "very", "just",
            "and", "but", "if", "or", "because", "about",  "during", "i",
            "me", "my", "we", "our", "you", "your", "it", "its", "they", "their", "tell",
            "help", "want", "know", "like", "get", "use", "using"
    );

    @PostConstruct
    public void loadKnowledge() throws Exception {
        ClassPathResource res = new ClassPathResource("chatbot/knowledge.json");
        try (InputStream in = res.getInputStream()) {
            knowledge = objectMapper.readValue(in, KnowledgeRoot.class);
        }
        log.info("Loaded {} chatbot topics", knowledge.getTopics().size());
    }

    public ChatbotMessageResponse answer(String rawMessage, String userRole, boolean publicOnly) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return ChatbotMessageResponse.builder()
                    .reply("Ask me anything about ShareBite: donations, requests, volunteering, AI freshness, or composting.")
                    .matches(List.of())
                    .sourceMode("fallback")
                    .suggestedOpeners(knowledge.getSuggestedOpeners())
                    .build();
        }

        String role = userRole != null ? userRole.toUpperCase(Locale.ROOT) : "GUEST";
        List<String> tokens = tokenize(rawMessage);

        List<ScoredTopic> scored = new ArrayList<>();
        for (KnowledgeTopic t : knowledge.getTopics()) {
            if (publicOnly && !t.isTopicPublic()) {
                continue;
            }
            if (!roleMatches(t.getRoles(), role)) {
                continue;
            }
            double score = scoreTopic(t, tokens, rawMessage);
            if (score > 0) {
                scored.add(new ScoredTopic(t, score));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        List<ScoredTopic> top = scored.stream().limit(3).collect(Collectors.toList());

        String faqReply;
        List<ChatbotMatchDto> matches = new ArrayList<>();
        if (top.isEmpty()) {
            faqReply = "I could not match that to a specific topic. Try one of the suggested questions below, "
                    + "or describe donations, requests, volunteering, compost, or the AI food check.";
        } else {
            StringBuilder sb = new StringBuilder();
            for (ScoredTopic st : top) {
                KnowledgeTopic t = st.topic;
                matches.add(new ChatbotMatchDto(
                        t.getId(),
                        t.getTitle(),
                        t.getLink(),
                        t.getLinkLabel(),
                        Math.round(st.score * 10) / 10.0
                ));
                sb.append("**").append(t.getTitle()).append("**\n");
                sb.append(t.getBody()).append("\n\n");
            }
            faqReply = sb.toString().trim();
        }

        double bestScore = top.isEmpty() ? 0 : top.get(0).score;
        String sourceMode = "faq";
        StringBuilder reply = new StringBuilder(faqReply);

        if (ollamaEnabled && (bestScore < 2.0 || rawMessage.length() > 80)) {
            String ollamaText = tryOllama(rawMessage, role, faqReply);
            if (ollamaText != null && !ollamaText.isBlank()) {
                reply.append("\n\n---\n*Local AI (Ollama) adds:*\n").append(ollamaText.trim());
                sourceMode = "faq+ollama";
            }
        }

        return ChatbotMessageResponse.builder()
                .reply(reply.toString())
                .matches(matches)
                .sourceMode(sourceMode)
                .suggestedOpeners(knowledge.getSuggestedOpeners())
                .build();
    }

    private String tryOllama(String userMessage, String role, String faqContext) {
        String base = ollamaBaseUrl.trim().replaceAll("/+$", "");
        String url = base + "/api/generate";
        String system = """
                You are ShareBite Help, a short assistant for a food donation platform.
                Rules: Answer in under 120 words. Be practical. Do not invent features not mentioned in the context.
                User role: %s

                Context from knowledge base:
                %s
                """.formatted(role, faqContext.length() > 1500 ? faqContext.substring(0, 1500) + "…" : faqContext);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", ollamaModel);
        body.put("stream", false);
        body.put("prompt", system + "\n\nUser question: " + userMessage + "\n\nConcise answer:");

        try {
            RestTemplate rt = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> resp = rt.postForEntity(url, entity, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.getBody());
            return root.path("response").asText(null);
        } catch (Exception e) {
            log.debug("Ollama unavailable or error: {}", e.getMessage());
            return null;
        }
    }

    private boolean roleMatches(List<String> roles, String role) {
        if (roles == null || roles.isEmpty()) {
            return true;
        }
        for (String r : roles) {
            if ("ALL".equalsIgnoreCase(r) || r.equalsIgnoreCase(role)) {
                return true;
            }
        }
        return false;
    }

    private double scoreTopic(KnowledgeTopic t, List<String> tokens, String rawLower) {
        String hay = rawLower.toLowerCase(Locale.ROOT);
        double score = 0;
        if (t.getKeywords() != null) {
            for (String kw : t.getKeywords()) {
                if (kw == null || kw.isBlank()) continue;
                String k = kw.toLowerCase(Locale.ROOT);
                if (hay.contains(k)) {
                    score += 2.0;
                }
                for (String tok : tokens) {
                    if (tok.contains(k) || k.contains(tok)) {
                        score += 1.0;
                    }
                }
            }
        }
        if (t.getTitle() != null) {
            for (String w : tokenize(t.getTitle())) {
                if (tokens.contains(w) || hay.contains(w)) {
                    score += 0.4;
                }
            }
        }
        // Light match on answer body so paraphrases still surface relevant topics
        if (t.getBody() != null) {
            String bodyLower = t.getBody().toLowerCase(Locale.ROOT);
            for (String tok : tokens) {
                if (tok.length() > 2 && bodyLower.contains(tok)) {
                    score += 0.35;
                }
            }
        }
        return score;
    }

    private List<String> tokenize(String s) {
        return Arrays.stream(s.toLowerCase(Locale.ROOT).split("\\W+"))
                .filter(w -> w.length() > 1)
                .filter(w -> !STOPWORDS.contains(w))
                .collect(Collectors.toList());
    }

    private record ScoredTopic(KnowledgeTopic topic, double score) {}

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KnowledgeRoot {
        private List<String> suggestedOpeners = List.of();
        private List<KnowledgeTopic> topics = List.of();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KnowledgeTopic {
        private String id;
        private List<String> keywords = List.of();
        private List<String> roles = List.of();

        @JsonProperty("public")
        private boolean topicPublic;

        private String title;
        private String body;
        private String link;
        private String linkLabel;
    }
}
