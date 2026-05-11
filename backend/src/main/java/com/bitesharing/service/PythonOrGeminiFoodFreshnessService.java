package com.bitesharing.service;

import com.bitesharing.dto.FoodFreshnessResult;
import com.bitesharing.dto.FreshnessMlPredictResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Server-side routing: local Keras API first; if it fails or is down, Gemini (if configured).
 * Set {@code freshness.mode=PYTHON_OR_GEMINI}.
 */
@Service
@ConditionalOnProperty(value = "freshness.mode", havingValue = "PYTHON_OR_GEMINI")
@Slf4j
@RequiredArgsConstructor
public class PythonOrGeminiFoodFreshnessService implements FoodFreshnessService {

    private final FreshnessMlClientService freshnessMlClientService;
    private final GeminiVisionFreshnessClient geminiVisionFreshnessClient;

    @Override
    public FoodFreshnessResult analyze(byte[] imageBytes, String filename) {
        long start = System.currentTimeMillis();
        try {
            FreshnessMlPredictResponse py = freshnessMlClientService.predictBytes(imageBytes, filename);
            return fromPython(py, start);
        } catch (Exception e) {
            log.warn("Python CNN freshness unavailable ({}), trying Gemini.", e.getMessage());
            if (!geminiVisionFreshnessClient.isConfigured()) {
                throw new RuntimeException(
                        "Python freshness service failed and Gemini is not configured (gemini.api.key).", e);
            }
            FoodFreshnessResult g = geminiVisionFreshnessClient.analyze(imageBytes, filename);
            if ("UNKNOWN".equalsIgnoreCase(g.getStatus())) {
                throw new RuntimeException("Python failed and Gemini could not classify the image.", e);
            }
            return g;
        }
    }

    private static FoodFreshnessResult fromPython(FreshnessMlPredictResponse r, long start) {
        long ms = System.currentTimeMillis() - start;
        return FoodFreshnessResult.builder()
                .status(r.getStatus())
                .confidence(r.getConfidence())
                .modelUsed("python-cnn")
                .processingTimeMs(ms)
                .message(r.getMessage())
                .estimatedExpiryHours(r.getEstimatedExpiryHours())
                .classProbabilities(r.getClassProbabilities())
                .predictedClassIndex(r.getPredictedClassIndex())
                .build();
    }
}
