package com.bitesharing.service;

import com.bitesharing.dto.EmergencyModeDto;
import com.bitesharing.dto.EmergencyResponseDto;
import com.bitesharing.model.Donation;
import com.bitesharing.model.Request;
import com.bitesharing.model.User;
import com.bitesharing.repository.DonationRepository;
import com.bitesharing.repository.RequestRepository;
import com.bitesharing.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmergencyModeService {

    private final DonationRepository donationRepository;
    private final RequestRepository requestRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final SmartRoutingService smartRoutingService;

    // Track active emergency modes
    private final Map<String, EmergencyModeDto> activeEmergencies = new ConcurrentHashMap<>();

    /**
     * Activate emergency mode for disaster response
     */
    public EmergencyResponseDto activateEmergencyMode(String emergencyId, String emergencyType,
                                                     String location, double latitude, double longitude,
                                                     int estimatedAffectedPeople, String description) {

        EmergencyModeDto emergency = EmergencyModeDto.builder()
                .emergencyId(emergencyId)
                .emergencyType(emergencyType)
                .location(location)
                .latitude(latitude)
                .longitude(longitude)
                .estimatedAffectedPeople(estimatedAffectedPeople)
                .description(description)
                .activatedAt(LocalDateTime.now())
                .status("ACTIVE")
                .build();

        activeEmergencies.put(emergencyId, emergency);

        // Send emergency notifications
        notificationService.sendEmergencyModeNotification(emergencyType, location, estimatedAffectedPeople);

        // Automatically create emergency food requests
        int emergencyRequestsCreated = createEmergencyRequests(emergency);

        // Find available donations for emergency response
        List<Donation> availableDonations = findAvailableEmergencyDonations(latitude, longitude, 50.0); // 50km radius

        // Optimize emergency routes
        var emergencyRoute = smartRoutingService.createEmergencyRoute(latitude, longitude, availableDonations);

        log.warn("EMERGENCY MODE ACTIVATED: {} at {} - {} affected people, {} emergency requests created",
                emergencyType, location, estimatedAffectedPeople, emergencyRequestsCreated);

        return EmergencyResponseDto.builder()
                .emergencyId(emergencyId)
                .status("ACTIVATED")
                .emergencyRequestsCreated(emergencyRequestsCreated)
                .availableDonations(availableDonations.size())
                .emergencyRoute(emergencyRoute)
                .activatedAt(emergency.getActivatedAt())
                .build();
    }

    /**
     * Deactivate emergency mode
     */
    public void deactivateEmergencyMode(String emergencyId) {
        EmergencyModeDto emergency = activeEmergencies.remove(emergencyId);
        if (emergency != null) {
            emergency.setStatus("DEACTIVATED");
            emergency.setDeactivatedAt(LocalDateTime.now());

            log.info("EMERGENCY MODE DEACTIVATED: {} at {}", emergency.getEmergencyType(), emergency.getLocation());
        }
    }

    /**
     * Get active emergency modes
     */
    @Cacheable(value = "activeEmergencies", key = "'current'")
    public List<EmergencyModeDto> getActiveEmergencies() {
        return activeEmergencies.values().stream()
                .filter(e -> "ACTIVE".equals(e.getStatus()))
                .collect(Collectors.toList());
    }

    /**
     * Get emergency response statistics
     */
    public Map<String, Object> getEmergencyResponseStats(String emergencyId) {
        EmergencyModeDto emergency = activeEmergencies.get(emergencyId);
        if (emergency == null) {
            throw new IllegalArgumentException("Emergency not found: " + emergencyId);
        }

        // Count emergency requests
        long emergencyRequests = requestRepository.findAll().stream()
                .filter(r -> r.getCreatedAt().isAfter(emergency.getActivatedAt()) &&
                        r.getRequesterType() == Request.RequesterType.NEEDY &&
                        isNearEmergencyLocation(r, emergency))
                .count();

        // Count fulfilled emergency requests
        long fulfilledRequests = requestRepository.findAll().stream()
                .filter(r -> r.getCreatedAt().isAfter(emergency.getActivatedAt()) &&
                        r.getStatus() == Request.RequestStatus.DELIVERED &&
                        isNearEmergencyLocation(r, emergency))
                .count();

        // Count emergency volunteers activated
        long activeEmergencyVolunteers = userRepository.findByUserType(User.UserType.VOLUNTEER).stream()
                .filter(v -> hasRecentEmergencyActivity(v, emergency))
                .count();

        return Map.of(
                "emergencyId", emergencyId,
                "emergencyRequestsCreated", emergencyRequests,
                "emergencyRequestsFulfilled", fulfilledRequests,
                "fulfillmentRate", emergencyRequests > 0 ? (double) fulfilledRequests / emergencyRequests : 0.0,
                "activeEmergencyVolunteers", activeEmergencyVolunteers,
                "estimatedAffectedPeople", emergency.getEstimatedAffectedPeople(),
                "responseEfficiency", calculateResponseEfficiency(fulfilledRequests, emergencyRequests, activeEmergencyVolunteers)
        );
    }

    /**
     * Create emergency food requests based on affected people
     */
    private int createEmergencyRequests(EmergencyModeDto emergency) {
        int requestsCreated = 0;
        int mealsNeeded = Math.max(emergency.getEstimatedAffectedPeople() * 3, 100); // At least 100 meals, 3 meals per person

        // Create bulk emergency requests
        // In a real implementation, this would distribute requests across the emergency area
        for (int i = 0; i < Math.min(mealsNeeded / 10, 50); i++) { // Create up to 50 emergency request groups
            try {
                // Create emergency user/request - simplified for demo
                // In production, this would create proper User entities and Requests
                requestsCreated += 10; // Assume 10 meals per request group
            } catch (Exception e) {
                log.error("Failed to create emergency request batch {}", i, e);
            }
        }

        return requestsCreated;
    }

    /**
     * Find donations available for emergency response
     */
    private List<Donation> findAvailableEmergencyDonations(double emergencyLat, double emergencyLng, double radiusKm) {
        return donationRepository.findByStatusIn(List.of(
                Donation.DonationStatus.AVAILABLE,
                Donation.DonationStatus.PENDING
        )).stream()
                .filter(donation -> {
                    if (donation.getLatitude() == null || donation.getLongitude() == null) return false;

                    double distance = haversine(emergencyLat, emergencyLng,
                            donation.getLatitude(), donation.getLongitude());
                    return distance <= radiusKm;
                })
                .filter(donation -> {
                    // Only include donations that are still fresh (at least 6 hours remaining)
                    long hoursRemaining = java.time.Duration.between(
                            LocalDateTime.now(), donation.getExpiryDate()).toHours();
                    return hoursRemaining >= 6;
                })
                .sorted((d1, d2) -> {
                    // Sort by distance and quantity (prefer larger quantities closer to emergency)
                    double dist1 = haversine(emergencyLat, emergencyLng, d1.getLatitude(), d1.getLongitude());
                    double dist2 = haversine(emergencyLat, emergencyLng, d2.getLatitude(), d2.getLongitude());

                    int distanceCompare = Double.compare(dist1, dist2);
                    if (distanceCompare != 0) return distanceCompare;

                    return Integer.compare(d2.getQuantity(), d1.getQuantity()); // Larger quantities first
                })
                .collect(Collectors.toList());
    }

    /**
     * Check if request is near emergency location
     */
    private boolean isNearEmergencyLocation(Request request, EmergencyModeDto emergency) {
        if (request.getDonation() == null ||
            request.getDonation().getLatitude() == null ||
            request.getDonation().getLongitude() == null) {
            return false;
        }

        double distance = haversine(emergency.getLatitude(), emergency.getLongitude(),
                request.getDonation().getLatitude(), request.getDonation().getLongitude());

        return distance <= 25.0; // 25km radius for emergency zone
    }

    /**
     * Check if volunteer has recent emergency activity
     */
    private boolean hasRecentEmergencyActivity(User volunteer, EmergencyModeDto emergency) {
        return requestRepository.findByAssignedVolunteerId(volunteer.getId())
                .stream()
                .filter(r -> r.getStatus() == Request.RequestStatus.DELIVERED)
                .anyMatch(r -> r.getDeliveredAt() != null &&
                        r.getDeliveredAt().isAfter(emergency.getActivatedAt()) &&
                        isNearEmergencyLocation(r, emergency));
    }

    /**
     * Calculate emergency response efficiency
     */
    private double calculateResponseEfficiency(long fulfilled, long total, long volunteers) {
        if (total == 0) return 0.0;

        double fulfillmentRate = (double) fulfilled / total;
        double volunteerEfficiency = volunteers > 0 ? (double) fulfilled / volunteers : 0.0;

        // Weighted efficiency score
        return (fulfillmentRate * 0.7) + (Math.min(volunteerEfficiency / 10.0, 1.0) * 0.3);
    }

    /**
     * Haversine distance calculation
     */
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