package com.bitesharing.service;

import com.bitesharing.dto.SmartMatchDto;
import com.bitesharing.model.Donation;
import com.bitesharing.model.User;
import com.bitesharing.repository.DonationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MatchingService {

    private final DonationRepository donationRepository;
    private final ExpiryRiskService expiryRiskService;

    public List<SmartMatchDto> getSmartMatchesForNgo(User ngoUser) {
        List<Donation> pending = donationRepository.findByStatusIn(List.of(
                Donation.DonationStatus.AVAILABLE,
                Donation.DonationStatus.PENDING
        ));
        return pending.stream()
                .map(donation -> toSmartMatch(donation, ngoUser))
                .sorted(Comparator.comparingDouble(SmartMatchDto::getPriorityScore).reversed())
                .toList();
    }

    public double calculatePriorityScore(double distanceKm, long ageMinutes, Double freshnessConfidence) {
        double freshness = freshnessConfidence == null ? 0.5 : freshnessConfidence;
        double distanceScore = Math.max(0, 1 - (distanceKm / 50.0));
        double ageScore = Math.min(1.0, ageMinutes / 120.0);
        return (distanceScore * 0.4) + (ageScore * 0.35) + (freshness * 0.25);
    }

    private SmartMatchDto toSmartMatch(Donation donation, User ngoUser) {
        double distanceKm = 0;
        if (ngoUser.getLatitude() != null && ngoUser.getLongitude() != null
                && donation.getLatitude() != null && donation.getLongitude() != null) {
            distanceKm = haversine(ngoUser.getLatitude(), ngoUser.getLongitude(), donation.getLatitude(), donation.getLongitude());
        }
        long ageMinutes = Duration.between(donation.getCreatedAt(), LocalDateTime.now()).toMinutes();
        double priority = calculatePriorityScore(distanceKm, ageMinutes, donation.getMlFreshnessConfidence());
        return new SmartMatchDto(
                donation,
                distanceKm,
                ageMinutes,
                donation.getMlFreshnessConfidence(),
                priority,
                expiryRiskService.getRemainingMinutes(donation),
                expiryRiskService.getRiskLevel(donation)
        );
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        final double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusKm * c;
    }
}
