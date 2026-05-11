package com.bitesharing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FoodFreshnessResult {
    private String status;
    private Double confidence;
    private String modelUsed;
    private Long processingTimeMs;
    private String message;
    /** From Python CNN rules; null if engine did not compute a shelf-life hint. */
    private Integer estimatedExpiryHours;
    /** Per-class softmax (e.g. fresh / rotten) when the CNN returned them. */
    private Map<String, Double> classProbabilities;
    private Integer predictedClassIndex;
}
