package com.bitesharing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VolunteerPerformanceDto {
    private Long volunteerId;
    private int totalDeliveries;
    private double averageRating;
    private double onTimeDeliveryRate;
    private double successfulDeliveryRate;
    private double performanceScore;
    private int recentDeliveries;
    private double recentAverageRating;
    private List<String> strengths;
    private List<String> areasForImprovement;
    private String performanceLevel;
}