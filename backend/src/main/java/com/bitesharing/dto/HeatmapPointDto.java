package com.bitesharing.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class HeatmapPointDto {
    private double latitude;
    private double longitude;
    private long requestCount;
    private String demandLevel;
}
