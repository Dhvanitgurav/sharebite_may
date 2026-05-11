package com.bitesharing.service;

import com.bitesharing.dto.QualityRatingDto;
import com.bitesharing.dto.VolunteerPerformanceDto;
import com.bitesharing.model.Request;
import com.bitesharing.model.User;
import com.bitesharing.repository.RequestRepository;
import com.bitesharing.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class QualityAssuranceService {

    private final RequestRepository requestRepository;
    private final UserRepository userRepository;

    /**
     * Submit quality rating for a completed delivery
     */
    public void submitQualityRating(Long requestId, QualityRatingDto rating) {
        Request request = requestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Request not found"));

        if (request.getStatus() != Request.RequestStatus.DELIVERED) {
            throw new IllegalStateException("Can only rate delivered requests");
        }

        // Store rating in request (assuming we add rating fields to Request entity)
        // For now, we'll log it and could store in a separate table
        log.info("Quality rating submitted for request {}: food={}, packaging={}, timeliness={}, communication={}",
                requestId, rating.getFoodQuality(), rating.getPackaging(), rating.getTimeliness(), rating.getCommunication());

        // Update volunteer's performance metrics
        if (request.getAssignedVolunteer() != null) {
            updateVolunteerPerformance(request.getAssignedVolunteer(), rating);
        }
    }

    /**
     * Get volunteer performance metrics
     */
    @Cacheable(value = "volunteerPerformance", key = "#volunteerId")
    public VolunteerPerformanceDto getVolunteerPerformance(Long volunteerId) {
        User volunteer = userRepository.findById(volunteerId)
                .orElseThrow(() -> new RuntimeException("Volunteer not found"));

        List<Request> completedDeliveries = requestRepository.findByAssignedVolunteerId(volunteerId)
                .stream()
                .filter(r -> r.getStatus() == Request.RequestStatus.DELIVERED)
                .toList();

        if (completedDeliveries.isEmpty()) {
            return VolunteerPerformanceDto.builder()
                    .volunteerId(volunteerId)
                    .totalDeliveries(0)
                    .averageRating(0.0)
                    .onTimeDeliveryRate(0.0)
                    .successfulDeliveryRate(0.0)
                    .performanceScore(0.0)
                    .build();
        }

        // Calculate metrics
        double averageRating = calculateAverageRating(completedDeliveries);
        double onTimeDeliveryRate = calculateOnTimeRate(completedDeliveries);
        double successfulDeliveryRate = calculateSuccessRate(completedDeliveries);
        double performanceScore = calculatePerformanceScore(averageRating, onTimeDeliveryRate, successfulDeliveryRate);

        // Recent performance trend
        List<Request> recentDeliveries = completedDeliveries.stream()
                .filter(r -> r.getDeliveredAt() != null &&
                        r.getDeliveredAt().isAfter(LocalDateTime.now().minusDays(30)))
                .collect(Collectors.toList());

        double recentAverageRating = recentDeliveries.isEmpty() ? averageRating :
                calculateAverageRating(recentDeliveries);

        // Strengths and areas for improvement
        List<String> strengths = identifyStrengths(averageRating, onTimeDeliveryRate, successfulDeliveryRate);
        List<String> improvements = identifyImprovements(averageRating, onTimeDeliveryRate, successfulDeliveryRate);

        return VolunteerPerformanceDto.builder()
                .volunteerId(volunteerId)
                .totalDeliveries(completedDeliveries.size())
                .averageRating(Math.round(averageRating * 100.0) / 100.0)
                .onTimeDeliveryRate(Math.round(onTimeDeliveryRate * 100.0) / 100.0)
                .successfulDeliveryRate(Math.round(successfulDeliveryRate * 100.0) / 100.0)
                .performanceScore(Math.round(performanceScore * 100.0) / 100.0)
                .recentDeliveries(recentDeliveries.size())
                .recentAverageRating(Math.round(recentAverageRating * 100.0) / 100.0)
                .strengths(strengths)
                .areasForImprovement(improvements)
                .performanceLevel(getPerformanceLevel(performanceScore))
                .build();
    }

    /**
     * Get top performing volunteers
     */
    @Cacheable(value = "topPerformers", key = "'weekly'")
    public List<VolunteerPerformanceDto> getTopPerformers(int limit) {
        List<User> volunteers = userRepository.findByUserType(User.UserType.VOLUNTEER);

        return volunteers.stream()
                .map(volunteer -> getVolunteerPerformance(volunteer.getId()))
                .filter(perf -> perf.getTotalDeliveries() >= 5) // Minimum threshold
                .sorted((a, b) -> Double.compare(b.getPerformanceScore(), a.getPerformanceScore()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Quality assurance checklist for donations
     */
    public List<String> getQualityChecklist(String donationType) {
        List<String> baseChecks = Arrays.asList(
                "Food appears fresh and unspoiled",
                "Proper packaging to prevent contamination",
                "Temperature appropriate for food type",
                "Allergen information provided if applicable",
                "Quantity matches description",
                "Expiry date clearly visible and reasonable"
        );

        List<String> typeSpecificChecks = switch (donationType.toUpperCase()) {
            case "FRUIT", "VEGETABLES" -> Arrays.asList(
                    "No signs of mold or rot",
                    "Firm texture appropriate for type",
                    "Natural color and appearance"
            );
            case "DAIRY" -> Arrays.asList(
                    "Refrigerated properly",
                    "No off odors",
                    "Proper sealing"
            );
            case "BAKERY" -> Arrays.asList(
                    "No staleness or mold",
                    "Proper wrapping to maintain freshness",
                    "No foreign objects"
            );
            case "MEAT", "SEAFOOD" -> Arrays.asList(
                    "Proper refrigeration maintained",
                    "No discoloration or off odors",
                    "Safe handling indicators present"
            );
            default -> Arrays.asList(
                    "General food safety standards met",
                    "No visible contamination"
            );
        };

        List<String> allChecks = new ArrayList<>(baseChecks);
        allChecks.addAll(typeSpecificChecks);
        return allChecks;
    }

    /**
     * Report quality issue
     */
    public void reportQualityIssue(Long requestId, String issueType, String description, User reportedBy) {
        Request request = requestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Request not found"));

        log.warn("Quality issue reported for request {}: type={}, description={}, reportedBy={}",
                requestId, issueType, description, reportedBy.getId());

        // Could store in quality issues table, send notifications, etc.
        // For now, we'll update request status if severe
        if ("FOOD_SAFETY".equals(issueType) || "CONTAMINATION".equals(issueType)) {
            // Flag for immediate attention
            log.error("Critical quality issue reported: {}", description);
        }
    }

    private double calculateAverageRating(List<Request> deliveries) {
        // This would use actual rating data from the database
        // For now, simulate based on delivery success
        return deliveries.stream()
                .mapToDouble(request -> {
                    // Simulate rating based on timeliness and success
                    boolean onTime = request.getDeliveredAt() != null &&
                            request.getUpdatedAt() != null &&
                            java.time.Duration.between(request.getUpdatedAt(), request.getDeliveredAt()).toMinutes() < 120;

                    boolean successful = request.getStatus() == Request.RequestStatus.DELIVERED;

                    double rating = 3.0; // Base rating
                    if (onTime) rating += 1.0;
                    if (successful) rating += 1.0;

                    return Math.min(5.0, rating);
                })
                .average()
                .orElse(0.0);
    }

    private double calculateOnTimeRate(List<Request> deliveries) {
        long onTimeDeliveries = deliveries.stream()
                .filter(request -> request.getDeliveredAt() != null &&
                        request.getUpdatedAt() != null &&
                        java.time.Duration.between(request.getUpdatedAt(), request.getDeliveredAt()).toMinutes() < 120)
                .count();

        return deliveries.isEmpty() ? 0.0 : (double) onTimeDeliveries / deliveries.size();
    }

    private double calculateSuccessRate(List<Request> deliveries) {
        long successfulDeliveries = deliveries.stream()
                .filter(request -> request.getStatus() == Request.RequestStatus.DELIVERED)
                .count();

        return deliveries.isEmpty() ? 0.0 : (double) successfulDeliveries / deliveries.size();
    }

    private double calculatePerformanceScore(double avgRating, double onTimeRate, double successRate) {
        // Weighted score: 40% rating, 30% on-time, 30% success
        return (avgRating * 0.4) + (onTimeRate * 30.0) + (successRate * 30.0);
    }

    private List<String> identifyStrengths(double avgRating, double onTimeRate, double successRate) {
        List<String> strengths = new ArrayList<>();

        if (avgRating >= 4.5) strengths.add("Excellent food quality and service");
        else if (avgRating >= 4.0) strengths.add("Good overall performance");

        if (onTimeRate >= 0.9) strengths.add("Highly reliable delivery timing");
        else if (onTimeRate >= 0.8) strengths.add("Generally punctual deliveries");

        if (successRate >= 0.95) strengths.add("Exceptional delivery success rate");
        else if (successRate >= 0.9) strengths.add("Strong delivery completion rate");

        return strengths.isEmpty() ? Arrays.asList("Consistent performance") : strengths;
    }

    private List<String> identifyImprovements(double avgRating, double onTimeRate, double successRate) {
        List<String> improvements = new ArrayList<>();

        if (avgRating < 4.0) improvements.add("Focus on improving food quality and presentation");
        if (onTimeRate < 0.8) improvements.add("Work on delivery timing and route optimization");
        if (successRate < 0.9) improvements.add("Address delivery completion issues");

        return improvements.isEmpty() ? Arrays.asList("Continue excellent performance") : improvements;
    }

    private String getPerformanceLevel(double score) {
        if (score >= 85) return "EXCELLENT";
        if (score >= 75) return "VERY_GOOD";
        if (score >= 65) return "GOOD";
        if (score >= 55) return "SATISFACTORY";
        return "NEEDS_IMPROVEMENT";
    }

    private void updateVolunteerPerformance(User volunteer, QualityRatingDto rating) {
        // This would update a performance tracking table
        // For now, we'll just log the update
        log.info("Updated performance metrics for volunteer {} based on rating: {}",
                volunteer.getId(), rating);
    }
}