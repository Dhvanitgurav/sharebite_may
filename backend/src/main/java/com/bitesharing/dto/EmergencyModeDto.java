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
public class EmergencyModeDto {
    private String emergencyId;
    private String emergencyType;
    private String location;
    private double latitude;
    private double longitude;
    private int estimatedAffectedPeople;
    private String description;
    private LocalDateTime activatedAt;
    private LocalDateTime deactivatedAt;
    private String status;
}