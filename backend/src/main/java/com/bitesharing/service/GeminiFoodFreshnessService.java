package com.bitesharing.service;

import com.bitesharing.dto.FoodFreshnessResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Vision-only freshness via Google Gemini (see {@code gemini.model}, default Pro-class model).
 * Enable with {@code freshness.mode=GEMINI} and {@code gemini.api.key}.
 */
@Service
@ConditionalOnProperty(value = "freshness.mode", havingValue = "GEMINI")
@Slf4j
@RequiredArgsConstructor
public class GeminiFoodFreshnessService implements FoodFreshnessService {

    private final GeminiVisionFreshnessClient geminiVisionFreshnessClient;

    @Override
    public FoodFreshnessResult analyze(byte[] imageBytes, String filename) {
        return geminiVisionFreshnessClient.analyze(imageBytes, filename);
    }
}
