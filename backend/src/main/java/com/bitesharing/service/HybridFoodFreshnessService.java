package com.bitesharing.service;

import com.bitesharing.dto.FoodFreshnessResult;
import com.bitesharing.dto.FreshnessMlPredictResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * CNN (Python) first; if confidence is low or Python is down, Gemini second opinion when configured.
 * {@code freshness.mode=HYBRID}. Client-facing model label is still overridden in {@link DonationService}.
 */
@Service
@ConditionalOnProperty(value = "freshness.mode", havingValue = "HYBRID")
@Slf4j
public class HybridFoodFreshnessService implements FoodFreshnessService {

    private final FreshnessMlClientService freshnessMlClientService;
    private final GeminiVisionFreshnessClient geminiVisionFreshnessClient;

    @Value("${freshness.hybrid.gemini-fallback-threshold:0.58}")
    private double geminiFallbackThreshold;

    @Value("${gemini.api.key:}")
    private String geminiApiKey;

    public HybridFoodFreshnessService(
            FreshnessMlClientService freshnessMlClientService,
            GeminiVisionFreshnessClient geminiVisionFreshnessClient) {
        this.freshnessMlClientService = freshnessMlClientService;
        this.geminiVisionFreshnessClient = geminiVisionFreshnessClient;
    }

    @Override
    public FoodFreshnessResult analyze(byte[] imageBytes, String filename) {
        long start = System.currentTimeMillis();
        FoodFreshnessResult pythonLike = null;
        try {
            FreshnessMlPredictResponse py = freshnessMlClientService.predictBytes(imageBytes, filename);
            pythonLike = FoodFreshnessResult.builder()
                    .status(py.getStatus())
                    .confidence(py.getConfidence())
                    .modelUsed("internal-python")
                    .processingTimeMs(System.currentTimeMillis() - start)
                    .message(py.getMessage())
                    .estimatedExpiryHours(py.getEstimatedExpiryHours())
                    .classProbabilities(py.getClassProbabilities())
                    .predictedClassIndex(py.getPredictedClassIndex())
                    .build();
        } catch (Exception e) {
            log.warn("Hybrid: Python freshness unavailable, considering Gemini only: {}", e.getMessage());
        }

        boolean needGemini = pythonLike == null
                || pythonLike.getConfidence() < geminiFallbackThreshold
                || "UNKNOWN".equalsIgnoreCase(pythonLike.getStatus());

        if (!needGemini || geminiApiKey == null || geminiApiKey.isBlank() || !geminiVisionFreshnessClient.isConfigured()) {
            if (pythonLike != null) {
                return pythonLike;
            }
            return FoodFreshnessResult.builder()
                    .status("UNKNOWN")
                    .confidence(0.0)
                    .modelUsed("internal-fallback")
                    .processingTimeMs(System.currentTimeMillis() - start)
                    .message("Freshness engines unavailable. Configure Python API and/or gemini.api.key.")
                    .estimatedExpiryHours(0)
                    .classProbabilities(Map.of())
                    .predictedClassIndex(null)
                    .build();
        }

        FoodFreshnessResult gemini = geminiVisionFreshnessClient.analyzeForHybrid(imageBytes, filename);
        if ("UNKNOWN".equalsIgnoreCase(gemini.getStatus())) {
            if (pythonLike != null) {
                return pythonLike;
            }
            return gemini;
        }
        if (pythonLike == null) {
            return gemini;
        }
        return mergeSafetyFirst(pythonLike, gemini, start);
    }

    private FoodFreshnessResult mergeSafetyFirst(FoodFreshnessResult a, FoodFreshnessResult b, long start) {
        boolean rottenA = "ROTTEN".equalsIgnoreCase(a.getStatus());
        boolean rottenB = "ROTTEN".equalsIgnoreCase(b.getStatus());
        if (rottenA || rottenB) {
            double conf = Math.max(
                    rottenA ? a.getConfidence() : 0,
                    rottenB ? b.getConfidence() : 0
            );
            return FoodFreshnessResult.builder()
                    .status("ROTTEN")
                    .confidence(Math.min(0.99, conf > 0 ? conf : 0.75))
                    .modelUsed("internal-hybrid")
                    .processingTimeMs(System.currentTimeMillis() - start)
                    .message("Conservative merge: at least one engine flagged spoilage risk.")
                    .estimatedExpiryHours(0)
                    .classProbabilities(firstNonEmptyProbs(a.getClassProbabilities(), b.getClassProbabilities()))
                    .predictedClassIndex(1)
                    .build();
        }
        double conf = (a.getConfidence() + b.getConfidence()) / 2.0;
        Integer exp = a.getEstimatedExpiryHours() != null ? a.getEstimatedExpiryHours() : b.getEstimatedExpiryHours();
        if (exp == null && conf >= 0.6) {
            exp = 4;
        }
        Map<String, Double> mergedProbs = firstNonEmptyProbs(a.getClassProbabilities(), b.getClassProbabilities());
        return FoodFreshnessResult.builder()
                .status("FRESH")
                .confidence(Math.min(0.99, conf))
                .modelUsed("internal-hybrid")
                .processingTimeMs(System.currentTimeMillis() - start)
                .message("Engines agree on fresh classification.")
                .estimatedExpiryHours(exp)
                .classProbabilities(mergedProbs)
                .predictedClassIndex(a.getPredictedClassIndex() != null ? a.getPredictedClassIndex() : b.getPredictedClassIndex())
                .build();
    }

    private static Map<String, Double> firstNonEmptyProbs(Map<String, Double> a, Map<String, Double> b) {
        if (a != null && !a.isEmpty()) {
            return a;
        }
        if (b != null && !b.isEmpty()) {
            return b;
        }
        return Map.of();
    }
}
