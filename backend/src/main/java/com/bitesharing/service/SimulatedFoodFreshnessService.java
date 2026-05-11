package com.bitesharing.service;

import com.bitesharing.dto.FoodFreshnessResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Service
@ConditionalOnProperty(value = "freshness.mode", havingValue = "SIMULATED", matchIfMissing = true)
@Slf4j
public class SimulatedFoodFreshnessService implements FoodFreshnessService {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public FoodFreshnessResult analyze(byte[] imageBytes, String filename) {
        long start = System.currentTimeMillis();
        try {
            Thread.sleep(400 + RANDOM.nextInt(350));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        String normalizedName = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String status = deriveStatusFromFilename(normalizedName, imageBytes);
        double confidence = deriveConfidence(status);

        String message = "Simulated freshness check using filename and image data patterns.";
        log.debug("Simulated freshness result for {} -> {} ({})", filename, status, confidence);

        Map<String, Double> probs = new HashMap<>();
        if ("FRESH".equals(status)) {
            probs.put("fresh", confidence);
            probs.put("rotten", Math.max(0.0, 1.0 - confidence));
        } else {
            probs.put("rotten", confidence);
            probs.put("fresh", Math.max(0.0, 1.0 - confidence));
        }
        int predIdx = "FRESH".equals(status) ? 0 : 1;
        Integer expiry = "ROTTEN".equals(status) ? 0 : (confidence >= 0.6 ? 4 : 0);

        return FoodFreshnessResult.builder()
                .status(status)
                .confidence(confidence)
                .modelUsed("SIMULATED")
                .processingTimeMs(System.currentTimeMillis() - start)
                .message(message)
                .estimatedExpiryHours(expiry)
                .classProbabilities(probs)
                .predictedClassIndex(predIdx)
                .build();
    }

    private String deriveStatusFromFilename(String filename, byte[] imageBytes) {
        if (filename.contains("rotten") || filename.contains("bad") || filename.contains("mold") || filename.contains("spoiled")) {
            return "ROTTEN";
        }
        if (filename.contains("fresh") || filename.contains("good") || filename.contains("clean")) {
            return "FRESH";
        }
        if (filename.contains("dog") || filename.contains("compost")) {
            return RANDOM.nextBoolean() ? "FRESH" : "ROTTEN";
        }
        return (imageBytes.length % 3 == 0) ? "FRESH" : "ROTTEN";
    }

    private double deriveConfidence(String status) {
        double base = status.equals("FRESH") ? 0.78 : 0.80;
        return Math.min(0.99, base + RANDOM.nextDouble() * 0.18);
    }
}
