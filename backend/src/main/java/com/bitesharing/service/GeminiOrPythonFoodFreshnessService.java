package com.bitesharing.service;

import com.bitesharing.dto.FoodFreshnessResult;
import com.bitesharing.dto.FreshnessMlPredictResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Server-side routing: Gemini first (if API key present); on UNKNOWN or error, local Keras API.
 * Set {@code freshness.mode=GEMINI_OR_PYTHON}.
 */
@Service
@ConditionalOnProperty(value = "freshness.mode", havingValue = "GEMINI_OR_PYTHON")
@Slf4j
@RequiredArgsConstructor
public class GeminiOrPythonFoodFreshnessService implements FoodFreshnessService {

    private final FreshnessMlClientService freshnessMlClientService;
    private final GeminiVisionFreshnessClient geminiVisionFreshnessClient;

    @Override
    public FoodFreshnessResult analyze(byte[] imageBytes, String filename) {
        long start = System.currentTimeMillis();
        if (geminiVisionFreshnessClient.isConfigured()) {
            try {
                FoodFreshnessResult g = geminiVisionFreshnessClient.analyze(imageBytes, filename);
                if (!"UNKNOWN".equalsIgnoreCase(g.getStatus())) {
                    return g;
                }
                log.warn("Gemini returned UNKNOWN; falling back to Python CNN.");
            } catch (Exception e) {
                log.warn("Gemini failed ({}); falling back to Python CNN.", e.getMessage());
            }
        } else {
            log.debug("Gemini not configured; using Python CNN only.");
        }
        try {
            FreshnessMlPredictResponse py = freshnessMlClientService.predictBytes(imageBytes, filename);
            long ms = System.currentTimeMillis() - start;
            return FoodFreshnessResult.builder()
                    .status(py.getStatus())
                    .confidence(py.getConfidence())
                    .modelUsed("python-cnn")
                    .processingTimeMs(ms)
                    .message(py.getMessage())
                    .estimatedExpiryHours(py.getEstimatedExpiryHours())
                    .classProbabilities(py.getClassProbabilities())
                    .predictedClassIndex(py.getPredictedClassIndex())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Freshness failed: Gemini unavailable or inconclusive and Python CNN failed.", e);
        }
    }
}
