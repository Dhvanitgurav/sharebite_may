package com.bitesharing.service;

import com.bitesharing.model.Donation;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class ExpiryRiskService {

    public long getRemainingMinutes(Donation donation) {
        LocalDateTime expiry = donation.getExpiryDate();
        if (expiry == null) {
            LocalDateTime fallback = donation.getCreatedAt().plusHours(getDefaultShelfLifeHours(donation.getFoodName()));
            return Duration.between(LocalDateTime.now(), fallback).toMinutes();
        }
        return Duration.between(LocalDateTime.now(), expiry).toMinutes();
    }

    public String getRiskLevel(Donation donation) {
        long remainingMinutes = getRemainingMinutes(donation);
        if (remainingMinutes <= 60) {
            return "HIGH";
        }
        if (remainingMinutes <= 180) {
            return "MEDIUM";
        }
        return "LOW";
    }

    public long getDefaultShelfLifeHours(String foodName) {
        if (foodName == null) return 6;
        String normalized = foodName.toLowerCase();
        if (normalized.contains("chapati")) return 8;
        if (normalized.contains("rice")) return 6;
        if (normalized.contains("curry")) return 10;
        return 6;
    }
}
