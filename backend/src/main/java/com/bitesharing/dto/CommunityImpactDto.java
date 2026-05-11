package com.bitesharing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommunityImpactDto {
    private long mealsServedToday;
    private double carbonFootprintSavedKg;
    private long foodWastePreventedKg;
    private long activeVolunteers;
    private long activeNgos;
    private double impactScore;
    private LocalDateTime lastUpdated;
}