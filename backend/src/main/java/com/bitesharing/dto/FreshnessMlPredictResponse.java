package com.bitesharing.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/**
 * Mirrors the FastAPI {@code /predict} JSON (snake_case from Python service).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FreshnessMlPredictResponse {

    private String status;
    private double confidence;

    @JsonProperty("estimated_expiry_hours")
    private Integer estimatedExpiryHours;

    private String message;

    @JsonProperty("class_probabilities")
    private Map<String, Double> classProbabilities;

    @JsonProperty("predicted_class_index")
    private Integer predictedClassIndex;
}
