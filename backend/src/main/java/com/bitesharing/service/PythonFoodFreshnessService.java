package com.bitesharing.service;

import com.bitesharing.dto.FoodFreshnessResult;
import com.bitesharing.dto.FreshnessMlPredictResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Server-side donation freshness using the FastAPI Keras service ({@code freshness_fixed.h5}).
 * Enable with {@code freshness.mode=PYTHON} and run Python on {@code freshness.api.base-url}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = "freshness.mode", havingValue = "PYTHON")
public class PythonFoodFreshnessService implements FoodFreshnessService {

    private final FreshnessMlClientService freshnessMlClientService;

    @Override
    public FoodFreshnessResult analyze(byte[] imageBytes, String filename) {
        long start = System.currentTimeMillis();
        try {
            FreshnessMlPredictResponse r = freshnessMlClientService.predictBytes(imageBytes, filename);
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
        } catch (Exception e) {
            log.warn("Python freshness analyze failed: {}", e.getMessage());
            throw new RuntimeException("Python freshness service failed: " + e.getMessage(), e);
        }
    }
}
