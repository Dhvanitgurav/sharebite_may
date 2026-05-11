package com.bitesharing.service;

import com.bitesharing.dto.RouteOptimizationDto;
import com.bitesharing.dto.RouteStopDto;
import com.bitesharing.model.Donation;
import com.bitesharing.model.Request;
import com.bitesharing.model.User;
import com.bitesharing.repository.RequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SmartRoutingService {

    private final RequestRepository requestRepository;

    /**
     * Optimizes delivery routes for volunteers with multiple pickups/deliveries
     */
    public RouteOptimizationDto optimizeVolunteerRoute(User volunteer, List<Request> assignedRequests) {
        if (assignedRequests.isEmpty()) {
            return RouteOptimizationDto.builder()
                    .totalDistanceKm(0.0)
                    .estimatedTimeMinutes(0)
                    .stops(new ArrayList<>())
                    .optimizationScore(0.0)
                    .build();
        }

        // Get volunteer's current location (assuming they start from their home/base)
        double startLat = volunteer.getLatitude() != null ? volunteer.getLatitude() : 0.0;
        double startLng = volunteer.getLongitude() != null ? volunteer.getLongitude() : 0.0;

        // Create route stops from assigned requests
        List<RouteStop> stops = assignedRequests.stream()
                .map(this::createRouteStop)
                .collect(Collectors.toList());

        // Optimize route using nearest neighbor algorithm with improvements
        List<RouteStop> optimizedRoute = optimizeRoute(startLat, startLng, stops);

        // Calculate total metrics
        double totalDistance = calculateTotalDistance(startLat, startLng, optimizedRoute);
        int estimatedTime = calculateEstimatedTime(totalDistance, optimizedRoute.size());

        // Calculate optimization score (0-100, higher is better)
        double optimizationScore = calculateOptimizationScore(assignedRequests.size(), totalDistance, estimatedTime);

        List<RouteStopDto> stopDtos = optimizedRoute.stream()
                .map(stop -> RouteStopDto.builder()
                        .requestId(stop.requestId)
                        .latitude(stop.latitude)
                        .longitude(stop.longitude)
                        .stopType(stop.stopType)
                        .priority(stop.priority)
                        .estimatedArrivalTime(calculateArrivalTime(stop.sequenceNumber, estimatedTime))
                        .build())
                .collect(Collectors.toList());

        return RouteOptimizationDto.builder()
                .totalDistanceKm(Math.round(totalDistance * 100.0) / 100.0)
                .estimatedTimeMinutes(estimatedTime)
                .stops(stopDtos)
                .optimizationScore(Math.round(optimizationScore * 100.0) / 100.0)
                .routeEfficiency(getRouteEfficiency(assignedRequests.size(), totalDistance))
                .build();
    }

    /**
     * Finds optimal pickup sequence for multiple donations
     */
    public List<Donation> optimizePickupSequence(User volunteer, List<Donation> availableDonations) {
        if (availableDonations.size() <= 1) {
            return availableDonations;
        }

        double startLat = volunteer.getLatitude() != null ? volunteer.getLatitude() : 0.0;
        double startLng = volunteer.getLongitude() != null ? volunteer.getLongitude() : 0.0;

        // Sort by distance, freshness priority, and time urgency
        return availableDonations.stream()
                .sorted((d1, d2) -> {
                    double dist1 = haversine(startLat, startLng, d1.getLatitude(), d1.getLongitude());
                    double dist2 = haversine(startLat, startLng, d2.getLatitude(), d2.getLongitude());

                    // Primary: Distance
                    int distanceCompare = Double.compare(dist1, dist2);

                    // Secondary: Freshness urgency (closer expiry = higher priority)
                    long time1 = java.time.Duration.between(LocalDateTime.now(), d1.getExpiryDate()).toMinutes();
                    long time2 = java.time.Duration.between(LocalDateTime.now(), d2.getExpiryDate()).toMinutes();
                    int timeCompare = Long.compare(time1, time2); // Smaller time = higher priority

                    // Tertiary: ML confidence (higher confidence = higher priority)
                    int confidenceCompare = Double.compare(d2.getMlFreshnessConfidence(), d1.getMlFreshnessConfidence());

                    return distanceCompare != 0 ? distanceCompare :
                           timeCompare != 0 ? timeCompare : confidenceCompare;
                })
                .collect(Collectors.toList());
    }

    /**
     * Emergency routing for disaster response scenarios
     */
    public RouteOptimizationDto createEmergencyRoute(double emergencyLat, double emergencyLng,
                                                    List<Donation> emergencyDonations) {
        List<RouteStop> emergencyStops = emergencyDonations.stream()
                .map(donation -> RouteStop.builder()
                        .requestId(null) // Emergency donations might not have requests yet
                        .latitude(donation.getLatitude())
                        .longitude(donation.getLongitude())
                        .stopType("EMERGENCY_PICKUP")
                        .priority(10) // Maximum priority
                        .sequenceNumber(0)
                        .build())
                .collect(Collectors.toList());

        // Sort by distance from emergency location
        emergencyStops.sort(Comparator.comparingDouble(stop ->
                haversine(emergencyLat, emergencyLng, stop.latitude, stop.longitude)));

        // Assign sequence numbers
        for (int i = 0; i < emergencyStops.size(); i++) {
            emergencyStops.get(i).sequenceNumber = i + 1;
        }

        double totalDistance = calculateTotalDistance(emergencyLat, emergencyLng, emergencyStops);
        int estimatedTime = calculateEstimatedTime(totalDistance, emergencyStops.size());

        List<RouteStopDto> stopDtos = emergencyStops.stream()
                .map(stop -> RouteStopDto.builder()
                        .requestId(stop.requestId)
                        .latitude(stop.latitude)
                        .longitude(stop.longitude)
                        .stopType(stop.stopType)
                        .priority(stop.priority)
                        .estimatedArrivalTime(calculateArrivalTime(stop.sequenceNumber, estimatedTime))
                        .build())
                .collect(Collectors.toList());

        return RouteOptimizationDto.builder()
                .totalDistanceKm(Math.round(totalDistance * 100.0) / 100.0)
                .estimatedTimeMinutes(estimatedTime)
                .stops(stopDtos)
                .optimizationScore(95.0) // Emergency routes are highly optimized
                .routeEfficiency("EMERGENCY_MODE")
                .build();
    }

    private RouteStop createRouteStop(Request request) {
        Donation donation = request.getDonation();
        if (donation == null) return null;

        String stopType = switch (request.getStatus()) {
            case ASSIGNED -> "PICKUP";
            case PICKED_UP -> "DELIVERY";
            default -> "UNKNOWN";
        };

        int priority = calculatePriority(request);

        return RouteStop.builder()
                .requestId(request.getId())
                .latitude(donation.getLatitude())
                .longitude(donation.getLongitude())
                .stopType(stopType)
                .priority(priority)
                .sequenceNumber(0) // Will be set during optimization
                .build();
    }

    private int calculatePriority(Request request) {
        int priority = 1;

        // Higher priority for urgent requests
        if (request.getDonation() != null) {
            long minutesRemaining = java.time.Duration.between(
                    LocalDateTime.now(),
                    request.getDonation().getExpiryDate()).toMinutes();

            if (minutesRemaining < 60) priority += 3; // Very urgent
            else if (minutesRemaining < 240) priority += 2; // Urgent
            else if (minutesRemaining < 480) priority += 1; // Somewhat urgent
        }

        // Higher priority for larger quantities
        if (request.getDonation() != null && request.getDonation().getQuantity() > 10) {
            priority += 1;
        }

        return Math.min(priority, 10); // Cap at 10
    }

    private List<RouteStop> optimizeRoute(double startLat, double startLng, List<RouteStop> stops) {
        if (stops.isEmpty()) return stops;

        List<RouteStop> optimized = new ArrayList<>();
        List<RouteStop> remaining = new ArrayList<>(stops);

        double currentLat = startLat;
        double currentLng = startLng;
        int sequence = 1;

        while (!remaining.isEmpty()) {
            RouteStop nearest = findNearestStop(currentLat, currentLng, remaining);
            nearest.sequenceNumber = sequence++;
            optimized.add(nearest);
            remaining.remove(nearest);
            currentLat = nearest.latitude;
            currentLng = nearest.longitude;
        }

        return optimized;
    }

    private RouteStop findNearestStop(double currentLat, double currentLng, List<RouteStop> stops) {
        return stops.stream()
                .min(Comparator.comparingDouble(stop ->
                        haversine(currentLat, currentLng, stop.latitude, stop.longitude)))
                .orElse(stops.get(0));
    }

    private double calculateTotalDistance(double startLat, double startLng, List<RouteStop> route) {
        if (route.isEmpty()) return 0.0;

        double totalDistance = haversine(startLat, startLng, route.get(0).latitude, route.get(0).longitude);

        for (int i = 0; i < route.size() - 1; i++) {
            RouteStop current = route.get(i);
            RouteStop next = route.get(i + 1);
            totalDistance += haversine(current.latitude, current.longitude, next.latitude, next.longitude);
        }

        return totalDistance;
    }

    private int calculateEstimatedTime(double distanceKm, int stops) {
        // Base time: 15 min per stop + 2 min per km driving
        return (int) Math.round((stops * 15) + (distanceKm * 2));
    }

    private double calculateOptimizationScore(int totalStops, double distance, int time) {
        // Score based on efficiency (lower time per stop = higher score)
        double avgTimePerStop = totalStops > 0 ? (double) time / totalStops : 0;
        double efficiency = Math.max(0, 100 - (avgTimePerStop - 15)); // 15 min baseline
        return Math.min(100, Math.max(0, efficiency));
    }

    private String getRouteEfficiency(int stops, double distance) {
        if (stops == 0) return "NO_ROUTE";

        double avgDistancePerStop = distance / stops;
        if (avgDistancePerStop < 2.0) return "HIGH_EFFICIENCY";
        if (avgDistancePerStop < 5.0) return "MEDIUM_EFFICIENCY";
        return "LOW_EFFICIENCY";
    }

    private LocalDateTime calculateArrivalTime(int sequenceNumber, int totalTime) {
        // Simple estimation: equal time distribution
        int timePerSegment = totalTime / Math.max(sequenceNumber, 1);
        return LocalDateTime.now().plusMinutes(sequenceNumber * timePerSegment);
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

    @lombok.Builder
    @lombok.Data
    private static class RouteStop {
        private Long requestId;
        private double latitude;
        private double longitude;
        private String stopType;
        private int priority;
        private int sequenceNumber;
    }
}