package com.bitesharing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityRatingDto {
    private double foodQuality; // 1-5
    private double packaging; // 1-5
    private double timeliness; // 1-5
    private double communication; // 1-5
}