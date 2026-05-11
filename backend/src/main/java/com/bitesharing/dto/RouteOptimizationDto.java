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
public class RouteOptimizationDto {
    private double totalDistanceKm;
    private int estimatedTimeMinutes;
    private List<RouteStopDto> stops;
    private double optimizationScore;
    private String routeEfficiency;
}