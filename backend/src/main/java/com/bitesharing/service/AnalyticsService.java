package com.bitesharing.service;

import com.bitesharing.dto.*;
import com.bitesharing.model.Request;
import com.bitesharing.model.Donation;
import com.bitesharing.repository.DonationRepository;
import com.bitesharing.repository.RequestRepository;
import com.bitesharing.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@EnableCaching
public class AnalyticsService {

    private final DonationRepository donationRepository;
    private final RequestRepository requestRepository;
    private final UserRepository userRepository;

    @Cacheable(value = "impactMetrics", key = "'current'")
    public ImpactMetricsDto getImpactMetrics() {
        long totalDonations = donationRepository.count();
        long mealsServed = requestRepository.countByStatus(Request.RequestStatus.DELIVERED);
        double co2SavedKg = mealsServed * 2.5; // 2.5kg CO2 saved per meal
        long compostCount = requestRepository.countByStatusIn(List.of(
                Request.RequestStatus.COMPOSTED,
                Request.RequestStatus.COMPOSTING,
                Request.RequestStatus.COMPLETED
        ));
        double estimatedWasteProcessedKg = compostCount * 0.5;

        // Calculate weekly growth
        LocalDateTime weekAgo = LocalDateTime.now().minusWeeks(1);
        long mealsLastWeek = requestRepository.findByStatus(Request.RequestStatus.DELIVERED)
                .stream()
                .filter(r -> r.getDeliveredAt() != null && r.getDeliveredAt().isAfter(weekAgo))
                .count();
        double weeklyGrowth = mealsLastWeek > 0 ? ((double)(mealsServed - mealsLastWeek) / mealsLastWeek) * 100 : 0;

        return new ImpactMetricsDto(totalDonations, mealsServed, co2SavedKg, compostCount, estimatedWasteProcessedKg, weeklyGrowth);
    }

    @Cacheable(value = "hungerHeatmap", key = "'current'")
    public List<HeatmapPointDto> getHungerHeatmap() {
        Map<String, long[]> bins = new HashMap<>();
        for (Request request : requestRepository.findAll()) {
            Donation donation = request.getDonation();
            if (donation == null || donation.getLatitude() == null || donation.getLongitude() == null) {
                continue;
            }
            double lat = round2(donation.getLatitude());
            double lng = round2(donation.getLongitude());
            String key = lat + ":" + lng;
            bins.putIfAbsent(key, new long[]{0, Double.doubleToLongBits(lat), Double.doubleToLongBits(lng)});
            bins.get(key)[0]++;
        }
        return bins.values().stream()
                .map(v -> new HeatmapPointDto(
                        Double.longBitsToDouble(v[1]),
                        Double.longBitsToDouble(v[2]),
                        v[0],
                        demandLevel(v[0])
                ))
                .toList();
    }

    @Cacheable(value = "demandForecast", key = "'daily'")
    public DemandForecastDto getDemandForecast() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime yesterday = now.minusDays(1);
        LocalDateTime lastWeek = now.minusDays(7);

        long todayRequests = requestRepository.countByCreatedAtAfter(yesterday);
        long weekRequests = requestRepository.countByCreatedAtAfter(lastWeek);

        // Simple trend analysis
        double dailyAverage = (double) weekRequests / 7.0;
        String trend = todayRequests > dailyAverage * 1.2 ? "INCREASING" :
                      todayRequests < dailyAverage * 0.8 ? "DECREASING" : "STABLE";

        String prediction = todayRequests > 30 ? "High demand expected tomorrow - prepare extra volunteers"
                : todayRequests > 10 ? "Medium demand expected tomorrow"
                : "Normal demand expected tomorrow";

        // Peak hours analysis
        Map<Integer, Long> hourlyStats = requestRepository.findAll().stream()
                .filter(r -> r.getCreatedAt().isAfter(lastWeek))
                .collect(Collectors.groupingBy(
                        r -> r.getCreatedAt().getHour(),
                        Collectors.counting()
                ));

        int peakHour = hourlyStats.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(19); // Default to 7 PM

        String peakTime = String.format("%02d:00 - %02d:00", peakHour, (peakHour + 1) % 24);

        return new DemandForecastDto(prediction, todayRequests, peakTime, trend, dailyAverage);
    }

    @Cacheable(value = "wasteAnalytics", key = "'weekly'")
    public FoodWasteAnalyticsDto getFoodWasteAnalytics() {
        LocalDateTime weekStart = LocalDate.now().minusDays(7).atStartOfDay();

        List<Donation> recentDonations = donationRepository.findAll().stream()
                .filter(d -> d.getCreatedAt().isAfter(weekStart))
                .toList();

        // Waste by food type
        Map<String, Long> wasteByType = recentDonations.stream()
                .filter(d -> d.getStatus() == Donation.DonationStatus.EXPIRED)
                .collect(Collectors.groupingBy(
                        d -> d.getDonationType().toString(),
                        Collectors.counting()
                ));

        // Peak donation hours
        Map<Integer, Long> donationsByHour = recentDonations.stream()
                .collect(Collectors.groupingBy(
                        d -> d.getCreatedAt().getHour(),
                        Collectors.counting()
                ));

        // High-risk donations (expiring within 4 hours)
        List<Donation> currentAvailable = donationRepository.findByStatusIn(List.of(
                Donation.DonationStatus.AVAILABLE,
                Donation.DonationStatus.PENDING
        ));

        long highRiskCount = currentAvailable.stream()
                .filter(d -> {
                    long hoursRemaining = ChronoUnit.HOURS.between(LocalDateTime.now(), d.getExpiryDate());
                    return hoursRemaining < 4;
                })
                .count();

        // Waste prevention rate
        long rescuedDonations = recentDonations.stream()
                .filter(d -> d.getStatus() == Donation.DonationStatus.DELIVERED ||
                        d.getStatus() == Donation.DonationStatus.COMPOSTED)
                .count();

        double wastePreventionRate = recentDonations.isEmpty() ? 0.0 :
                (double) rescuedDonations / recentDonations.size();

        // Freshness distribution
        Map<String, Long> freshnessStats = recentDonations.stream()
                .filter(d -> d.getFreshnessStatus() != null)
                .collect(Collectors.groupingBy(
                        d -> d.getFreshnessStatus(),
                        Collectors.counting()
                ));

        return FoodWasteAnalyticsDto.builder()
                .wasteByFoodType(wasteByType)
                .peakDonationHours(donationsByHour)
                .highRiskDonationsCount(highRiskCount)
                .wastePreventionRate(wastePreventionRate)
                .totalDonationsAnalyzed(recentDonations.size())
                .freshnessDistribution(freshnessStats)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    public List<String> getWastePreventionRecommendations() {
        FoodWasteAnalyticsDto analytics = getFoodWasteAnalytics();

        return List.of(
                String.format("🚨 High-risk donations: %d items expiring within 4 hours", analytics.getHighRiskDonationsCount()),
                String.format("✅ Waste prevention rate: %.1f%%", analytics.getWastePreventionRate() * 100),
                "💡 Consider increasing volunteer capacity during peak donation hours",
                "🤝 Partner with more NGOs for better distribution coverage",
                "📊 Monitor freshness distribution to optimize food quality",
                "⚡ Implement emergency pickup alerts for high-risk items"
        );
    }

    private String demandLevel(long count) {
        if (count >= 6) return "HIGH";
        if (count >= 3) return "MEDIUM";
        return "LOW";
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public Map<String, Object> generateUserReport() {
        // Generate PDF report data for users
        Map<String, Object> report = new HashMap<>();
        report.put("title", "User Activity Report");
        report.put("generatedAt", LocalDateTime.now());
        report.put("totalUsers", userRepository.count());
        report.put("totalRequests", requestRepository.count());
        report.put("totalDonations", donationRepository.count());
        report.put("deliveredMeals", requestRepository.countByStatus(Request.RequestStatus.DELIVERED));
        return report;
    }

    public Map<String, Object> generateDonationReport() {
        // Generate PDF report data for donations
        Map<String, Object> report = new HashMap<>();
        report.put("title", "Donation Analytics Report");
        report.put("generatedAt", LocalDateTime.now());
        report.put("totalDonations", donationRepository.count());
        report.put("successfulDeliveries", requestRepository.countByStatus(Request.RequestStatus.DELIVERED));
        report.put("wastePrevented", requestRepository.countByStatus(Request.RequestStatus.DELIVERED) * 0.5);
        report.put("donationTrends", "Increasing"); // Placeholder
        return report;
    }

    public Map<String, Object> generateImpactReport() {
        // Generate PDF report data for impact
        ImpactMetricsDto metrics = getImpactMetrics();
        Map<String, Object> report = new HashMap<>();
        report.put("title", "Community Impact Report");
        report.put("generatedAt", LocalDateTime.now());
        report.put("mealsServed", metrics.getMealsServed());
        report.put("co2Saved", metrics.getCo2SavedKg());
        report.put("wasteProcessed", metrics.getEstimatedWasteProcessedKg());
        report.put("weeklyGrowth", metrics.getWeeklyGrowth());
        return report;
    }

    public Map<String, Object> generateWasteReport() {
        // Generate PDF report data for waste
        FoodWasteAnalyticsDto waste = getFoodWasteAnalytics();
        Map<String, Object> report = new HashMap<>();
        report.put("title", "Food Waste Report");
        report.put("generatedAt", LocalDateTime.now());
        report.put("wastePreventionRate", waste.getWastePreventionRate());
        report.put("highRiskDonations", waste.getHighRiskDonationsCount());
        report.put("wasteByType", waste.getWasteByFoodType());
        return report;
    }

    public Map<String, Object> exportAllData() {
        // Export all data as CSV
        Map<String, Object> export = new HashMap<>();
        export.put("title", "Complete Data Export");
        export.put("generatedAt", LocalDateTime.now());
        export.put("users", List.of()); // Placeholder - would need UserRepository
        export.put("donations", donationRepository.findAll());
        export.put("requests", requestRepository.findAll());
        return export;
    }

    public Map<String, Object> generateMonthlySummary() {
        // Generate monthly summary report
        Map<String, Object> summary = new HashMap<>();
        summary.put("title", "Monthly Summary Report");
        summary.put("generatedAt", LocalDateTime.now());
        summary.put("period", "Last 30 days");
        summary.put("totalDonations", donationRepository.count());
        summary.put("totalRequests", requestRepository.count());
        summary.put("successfulDeliveries", requestRepository.countByStatus(Request.RequestStatus.DELIVERED));
        summary.put("wastePrevented", requestRepository.countByStatus(Request.RequestStatus.DELIVERED) * 0.5);
        return summary;
    }
}
