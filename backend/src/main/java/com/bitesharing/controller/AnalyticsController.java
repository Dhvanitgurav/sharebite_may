package com.bitesharing.controller;

import com.bitesharing.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsPdfExportService analyticsPdfExportService;
    private final AnalyticsCsvExportService analyticsCsvExportService;
    private final AnalyticsService analyticsService;
    private final GamificationService gamificationService;
    private final SmartRoutingService smartRoutingService;
    private final QualityAssuranceService qualityAssuranceService;
    private final EmergencyModeService emergencyModeService;

    @GetMapping("/impact")
    public ResponseEntity<?> getImpactMetrics() {
        return ResponseEntity.ok(analyticsService.getImpactMetrics());
    }

    @GetMapping("/community-impact")
    public ResponseEntity<?> getCommunityImpact() {
        return ResponseEntity.ok(analyticsService.getImpactMetrics());
    }

    @GetMapping("/waste-analytics")
    public ResponseEntity<?> getFoodWasteAnalytics() {
        return ResponseEntity.ok(analyticsService.getFoodWasteAnalytics());
    }

    @GetMapping("/waste-prevention-recommendations")
    public ResponseEntity<?> getWastePreventionRecommendations() {
        return ResponseEntity.ok(analyticsService.getWastePreventionRecommendations());
    }

    @GetMapping("/hunger-heatmap")
    public ResponseEntity<?> getHungerHeatmap() {
        return ResponseEntity.ok(analyticsService.getHungerHeatmap());
    }

    @GetMapping("/demand-forecast")
    public ResponseEntity<?> getDemandForecast() {
        return ResponseEntity.ok(analyticsService.getDemandForecast());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/user-report")
    public ResponseEntity<?> downloadUserReport(@RequestParam(defaultValue = "json") String format) {
        var body = analyticsService.generateUserReport();
        if ("pdf".equalsIgnoreCase(format)) {
            byte[] pdf = analyticsPdfExportService.toPdf(body, "User Activity Report");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"user-report.pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        }
        return ResponseEntity.ok(body);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/donation-report")
    public ResponseEntity<?> downloadDonationReport(@RequestParam(defaultValue = "json") String format) {
        var body = analyticsService.generateDonationReport();
        if ("pdf".equalsIgnoreCase(format)) {
            byte[] pdf = analyticsPdfExportService.toPdf(body, "Donation Analytics Report");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"donation-report.pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        }
        return ResponseEntity.ok(body);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/impact-report")
    public ResponseEntity<?> downloadImpactReport(@RequestParam(defaultValue = "json") String format) {
        var body = analyticsService.generateImpactReport();
        if ("pdf".equalsIgnoreCase(format)) {
            byte[] pdf = analyticsPdfExportService.toPdf(body, "Community Impact Report");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"impact-report.pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        }
        return ResponseEntity.ok(body);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/waste-report")
    public ResponseEntity<?> downloadWasteReport(@RequestParam(defaultValue = "json") String format) {
        var body = analyticsService.generateWasteReport();
        if ("pdf".equalsIgnoreCase(format)) {
            byte[] pdf = analyticsPdfExportService.toPdf(body, "Food Waste Report");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"waste-report.pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        }
        return ResponseEntity.ok(body);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/export-data")
    public ResponseEntity<?> exportAllData(@RequestParam(defaultValue = "json") String format) {
        var body = analyticsService.exportAllData();
        if ("pdf".equalsIgnoreCase(format)) {
            byte[] pdf = analyticsPdfExportService.toPdf(body, "Data Export");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"export-data.pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        }
        if ("csv".equalsIgnoreCase(format)) {
            byte[] csv = analyticsCsvExportService.toCsvBytes(body);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"export-data.csv\"")
                    .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .body(csv);
        }
        return ResponseEntity.ok(body);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/monthly-summary")
    public ResponseEntity<?> downloadMonthlySummary(@RequestParam(defaultValue = "json") String format) {
        var body = analyticsService.generateMonthlySummary();
        if ("pdf".equalsIgnoreCase(format)) {
            byte[] pdf = analyticsPdfExportService.toPdf(body, "Monthly Summary");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"monthly-summary.pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        }
        return ResponseEntity.ok(body);
    }

    // Gamification endpoints
    @GetMapping("/leaderboard/{category}/{timeframe}")
    public ResponseEntity<?> getLeaderboard(@PathVariable String category, @PathVariable String timeframe) {
        return ResponseEntity.ok(gamificationService.getLeaderboard(category, timeframe));
    }

    @GetMapping("/user/{userId}/stats")
    public ResponseEntity<?> getUserStats(@PathVariable Long userId) {
        return ResponseEntity.ok(gamificationService.getUserStats(userId));
    }

    @GetMapping("/challenges/daily")
    public ResponseEntity<?> getDailyChallenges() {
        return ResponseEntity.ok(gamificationService.getDailyChallenges());
    }

    // Quality Assurance endpoints
    @PostMapping("/quality-rating")
    public ResponseEntity<?> submitQualityRating(@RequestBody Map<String, Object> ratingData) {
        Long requestId = Long.valueOf(ratingData.get("requestId").toString());
        Map<String, Object> rating = (Map<String, Object>) ratingData.get("rating");

        qualityAssuranceService.submitQualityRating(requestId, new com.bitesharing.dto.QualityRatingDto(
                Double.valueOf(rating.get("foodQuality").toString()),
                Double.valueOf(rating.get("packaging").toString()),
                Double.valueOf(rating.get("timeliness").toString()),
                Double.valueOf(rating.get("communication").toString())
        ));

        return ResponseEntity.ok("Quality rating submitted successfully");
    }

    @GetMapping("/volunteer/{volunteerId}/performance")
    public ResponseEntity<?> getVolunteerPerformance(@PathVariable Long volunteerId) {
        return ResponseEntity.ok(qualityAssuranceService.getVolunteerPerformance(volunteerId));
    }

    @GetMapping("/quality-checklist/{donationType}")
    public ResponseEntity<?> getQualityChecklist(@PathVariable String donationType) {
        return ResponseEntity.ok(qualityAssuranceService.getQualityChecklist(donationType));
    }

    @PostMapping("/quality-issue")
    public ResponseEntity<?> reportQualityIssue(@RequestBody Map<String, Object> issueData) {
        Long requestId = Long.valueOf(issueData.get("requestId").toString());
        String issueType = issueData.get("issueType").toString();
        String description = issueData.get("description").toString();

        // For now, assume admin user - in real implementation get from security context
        qualityAssuranceService.reportQualityIssue(requestId, issueType, description, null);

        return ResponseEntity.ok("Quality issue reported successfully");
    }

    // Emergency Mode endpoints
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/emergency/activate")
    public ResponseEntity<?> activateEmergencyMode(@RequestBody Map<String, Object> emergencyData) {
        String emergencyId = emergencyData.get("emergencyId").toString();
        String emergencyType = emergencyData.get("emergencyType").toString();
        String location = emergencyData.get("location").toString();
        double latitude = Double.valueOf(emergencyData.get("latitude").toString());
        double longitude = Double.valueOf(emergencyData.get("longitude").toString());
        int affectedPeople = Integer.valueOf(emergencyData.get("affectedPeople").toString());
        String description = emergencyData.get("description").toString();

        var response = emergencyModeService.activateEmergencyMode(emergencyId, emergencyType, location,
                latitude, longitude, affectedPeople, description);

        return ResponseEntity.ok(response);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/emergency/{emergencyId}/deactivate")
    public ResponseEntity<?> deactivateEmergencyMode(@PathVariable String emergencyId) {
        emergencyModeService.deactivateEmergencyMode(emergencyId);
        return ResponseEntity.ok("Emergency mode deactivated");
    }

    @GetMapping("/emergency/active")
    public ResponseEntity<?> getActiveEmergencies() {
        return ResponseEntity.ok(emergencyModeService.getActiveEmergencies());
    }

    @GetMapping("/emergency/{emergencyId}/stats")
    public ResponseEntity<?> getEmergencyResponseStats(@PathVariable String emergencyId) {
        return ResponseEntity.ok(emergencyModeService.getEmergencyResponseStats(emergencyId));
    }

    // Smart Routing endpoints
    @PostMapping("/route/optimize")
    public ResponseEntity<?> optimizeRoute(@RequestBody Map<String, Object> routeData) {
        Long volunteerId = Long.valueOf(routeData.get("volunteerId").toString());
        List<Long> requestIds = (List<Long>) routeData.get("requestIds");

        // This would need proper implementation with user lookup
        // For now, return placeholder
        return ResponseEntity.ok("Route optimization feature coming soon");
    }

    @GetMapping("/top-performers/{limit}")
    public ResponseEntity<?> getTopPerformers(@PathVariable int limit) {
        return ResponseEntity.ok(qualityAssuranceService.getTopPerformers(limit));
    }
}
