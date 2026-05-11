package com.bitesharing.dto;

import com.bitesharing.model.Donation;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SmartMatchDto {
    private Donation donation;
    private double distanceKm;
    private long ageMinutes;
    private Double freshnessConfidence;
    private double priorityScore;
    private long remainingMinutes;
    private String riskLevel;
}
