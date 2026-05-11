package com.bitesharing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FoodWasteAnalyticsDto {
    private Map<String, Long> wasteByFoodType;
    private Map<Integer, Long> peakDonationHours;
    private long highRiskDonationsCount;
    private double wastePreventionRate;
    private long totalDonationsAnalyzed;
    private Map<String, Long> freshnessDistribution;
    private LocalDateTime generatedAt;
}