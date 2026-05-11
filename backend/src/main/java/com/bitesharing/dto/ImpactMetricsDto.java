package com.bitesharing.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ImpactMetricsDto {
    private long totalDonations;
    private long mealsServed;
    private double co2SavedKg;
    private long compostCount;
    private double estimatedWasteProcessedKg;
    private double weeklyGrowth;
}
