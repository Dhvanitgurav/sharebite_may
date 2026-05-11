package com.bitesharing.service;

import com.bitesharing.model.Request;
import com.bitesharing.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final SimpMessagingTemplate messagingTemplate;
    private final Map<Long, LocalDateTime> lastNotificationTime = new ConcurrentHashMap<>();

    /**
     * Send real-time notification to specific user
     */
    public void sendNotificationToUser(Long userId, String type, String message, Map<String, Object> data) {
        Map<String, Object> notification = new HashMap<>();
        notification.put("type", type);
        notification.put("message", message);
        notification.put("timestamp", LocalDateTime.now());
        notification.put("data", data != null ? data : new HashMap<>());

        String destination = "/user/" + userId + "/notifications";
        messagingTemplate.convertAndSend(destination, notification);

        log.info("Sent {} notification to user {}: {}", type, userId, message);
    }

    /**
     * Send notification to all users with specific role
     */
    public void sendNotificationToRole(User.UserType role, String type, String message, Map<String, Object> data) {
        Map<String, Object> notification = new HashMap<>();
        notification.put("type", type);
        notification.put("message", message);
        notification.put("timestamp", LocalDateTime.now());
        notification.put("data", data != null ? data : new HashMap<>());

        String destination = "/topic/role/" + role.name().toLowerCase();
        messagingTemplate.convertAndSend(destination, notification);

        log.info("Sent {} notification to all {} users: {}", type, role, message);
    }

    /**
     * Send urgent donation alert to nearby volunteers
     */
    public void sendUrgentDonationAlert(Request request, double radiusKm) {
        if (request.getDonation() == null) return;

        Map<String, Object> data = new HashMap<>();
        data.put("requestId", request.getId());
        data.put("donationType", request.getDonation().getDonationType());
        data.put("quantity", request.getDonation().getQuantity());
        data.put("latitude", request.getDonation().getLatitude());
        data.put("longitude", request.getDonation().getLongitude());
        data.put("urgency", calculateUrgency(request));

        String message = String.format("🚨 URGENT: %s donation needs pickup within %d hours!",
                request.getDonation().getDonationType(),
                getHoursUntilExpiry(request));

        sendNotificationToRole(User.UserType.VOLUNTEER, "URGENT_DONATION", message, data);
    }

    /**
     * Send delivery completion notification
     */
    public void sendDeliveryCompletedNotification(Request request) {
        if (request.getRequester() == null || request.getAssignedVolunteer() == null) return;

        // Notify requester
        Map<String, Object> requesterData = new HashMap<>();
        requesterData.put("requestId", request.getId());
        requesterData.put("volunteerName", request.getAssignedVolunteer().getFullName());
        requesterData.put("deliveredAt", request.getDeliveredAt());

        sendNotificationToUser(request.getRequester().getId(), "DELIVERY_COMPLETED",
                "Your food request has been delivered! Bon appétit! 🍽️", requesterData);

        // Notify volunteer
        Map<String, Object> volunteerData = new HashMap<>();
        volunteerData.put("requestId", request.getId());
        volunteerData.put("requesterName", request.getRequester().getFullName());
        volunteerData.put("impact", "Meal served to someone in need");

        sendNotificationToUser(request.getAssignedVolunteer().getId(), "VOLUNTEER_DELIVERY_COMPLETED",
                "Great job! You've successfully delivered another meal. 🌟", volunteerData);
    }

    /**
     * Send new request available notification to NGOs
     */
    public void sendNewRequestAvailableNotification(Request request) {
        Map<String, Object> data = new HashMap<>();
        data.put("requestId", request.getId());
        data.put("requesterType", request.getRequesterType());
        data.put("location", getLocationString(request));
        data.put("createdAt", request.getCreatedAt());

        String message = String.format("New %s request available for pickup", request.getRequesterType());

        sendNotificationToRole(User.UserType.NGO, "NEW_REQUEST", message, data);
    }

    /**
     * Send emergency mode activation notification
     */
    public void sendEmergencyModeNotification(String emergencyType, String location, int affectedPeople) {
        Map<String, Object> data = new HashMap<>();
        data.put("emergencyType", emergencyType);
        data.put("location", location);
        data.put("affectedPeople", affectedPeople);
        data.put("activatedAt", LocalDateTime.now());

        String message = String.format("🚨 EMERGENCY MODE ACTIVATED: %s in %s - %d people affected",
                emergencyType, location, affectedPeople);

        // Send to all volunteers and NGOs
        sendNotificationToRole(User.UserType.VOLUNTEER, "EMERGENCY_MODE", message, data);
        sendNotificationToRole(User.UserType.NGO, "EMERGENCY_MODE", message, data);
        sendNotificationToRole(User.UserType.ADMIN, "EMERGENCY_MODE", message, data);
    }

    /**
     * Send performance achievement notification
     */
    public void sendAchievementNotification(User user, String achievement, String description) {
        Map<String, Object> data = new HashMap<>();
        data.put("achievement", achievement);
        data.put("description", description);
        data.put("unlockedAt", LocalDateTime.now());

        String message = String.format("🏆 Achievement Unlocked: %s", achievement);

        sendNotificationToUser(user.getId(), "ACHIEVEMENT", message, data);
    }

    /**
     * Send rate limiting warning
     */
    public void sendRateLimitWarning(User user, int remainingRequests, LocalDateTime resetTime) {
        Map<String, Object> data = new HashMap<>();
        data.put("remainingRequests", remainingRequests);
        data.put("resetTime", resetTime);

        String message = String.format("⚠️ Rate limit warning: %d requests remaining today", remainingRequests);

        sendNotificationToUser(user.getId(), "RATE_LIMIT_WARNING", message, data);
    }

    /**
     * Send system maintenance notification
     */
    public void sendMaintenanceNotification(String maintenanceType, LocalDateTime scheduledTime, int durationMinutes) {
        Map<String, Object> data = new HashMap<>();
        data.put("maintenanceType", maintenanceType);
        data.put("scheduledTime", scheduledTime);
        data.put("durationMinutes", durationMinutes);

        String message = String.format("🔧 System maintenance scheduled: %s at %s (%d minutes)",
                maintenanceType, scheduledTime, durationMinutes);

        // Send to all users
        sendNotificationToRole(User.UserType.ADMIN, "MAINTENANCE", message, data);
        sendNotificationToRole(User.UserType.VOLUNTEER, "MAINTENANCE", message, data);
        sendNotificationToRole(User.UserType.NGO, "MAINTENANCE", message, data);
        sendNotificationToRole(User.UserType.HOTEL, "MAINTENANCE", message, data);
        sendNotificationToRole(User.UserType.NEEDY, "MAINTENANCE", message, data);
    }

    /**
     * Check if notification should be throttled to prevent spam
     */
    private boolean shouldThrottleNotification(Long userId, String type) {
        LocalDateTime lastTime = lastNotificationTime.get(userId);
        if (lastTime == null) return false;

        // Throttle similar notifications within 5 minutes
        return LocalDateTime.now().isBefore(lastTime.plusMinutes(5));
    }

    private int calculateUrgency(Request request) {
        if (request.getDonation() == null) return 1;

        long hoursUntilExpiry = getHoursUntilExpiry(request);
        if (hoursUntilExpiry <= 2) return 5; // Critical
        if (hoursUntilExpiry <= 6) return 4; // High
        if (hoursUntilExpiry <= 12) return 3; // Medium
        if (hoursUntilExpiry <= 24) return 2; // Low
        return 1; // Normal
    }

    private long getHoursUntilExpiry(Request request) {
        if (request.getDonation() == null || request.getDonation().getExpiryDate() == null) {
            return 24; // Default
        }
        return java.time.Duration.between(LocalDateTime.now(), request.getDonation().getExpiryDate()).toHours();
    }

    private String getLocationString(Request request) {
        if (request.getDonation() == null) return "Unknown location";

        double lat = request.getDonation().getLatitude() != null ? request.getDonation().getLatitude() : 0.0;
        double lng = request.getDonation().getLongitude() != null ? request.getDonation().getLongitude() : 0.0;

        return String.format("%.4f, %.4f", lat, lng);
    }
}