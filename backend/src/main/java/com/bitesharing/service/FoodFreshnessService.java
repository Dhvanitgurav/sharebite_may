package com.bitesharing.service;

import com.bitesharing.dto.FoodFreshnessResult;

public interface FoodFreshnessService {

    FoodFreshnessResult analyze(byte[] imageBytes, String filename);
}
