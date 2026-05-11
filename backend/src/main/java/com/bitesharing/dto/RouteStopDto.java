package com.bitesharing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteStopDto {
    private Long requestId;
    private double latitude;
    private double longitude;
    private String stopType;
    private int priority;
    private LocalDateTime estimatedArrivalTime;
}