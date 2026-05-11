package com.bitesharing.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DemandForecastDto {
    private String prediction;
    private long last7DaysRequests;
    private String peakWindow;
    private String trend;
    private double dailyAverage;
}
