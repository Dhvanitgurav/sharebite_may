package com.bitesharing.controller;

import com.bitesharing.model.Tracking;
import com.bitesharing.model.Request;
import com.bitesharing.model.User;
import com.bitesharing.repository.RequestRepository;
import com.bitesharing.repository.TrackingRepository;
import com.bitesharing.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/tracking")
@RequiredArgsConstructor
public class TrackingController {

    private final TrackingRepository trackingRepository;
    private final RequestRepository requestRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    private static final Duration MIN_PUSH_INTERVAL = Duration.ofSeconds(2);

    @PostMapping
    public ResponseEntity<Tracking> updateLocation(
            @RequestParam Long requestId,
            @RequestParam Double latitude,
            @RequestParam Double longitude,
            Authentication authentication) {
        try {
            if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
                return ResponseEntity.badRequest().build();
            }
            if (authentication == null || authentication.getName() == null) {
                return ResponseEntity.status(401).build();
            }

            User actor = userRepository.findByUsername(authentication.getName())
                    .orElseThrow(() -> new RuntimeException("Volunteer not found"));
            if (actor.getUserType() != User.UserType.VOLUNTEER) {
                return ResponseEntity.status(403).build();
            }

            Request request = requestRepository.findById(requestId)
                    .orElseThrow(() -> new RuntimeException("Request not found"));
            if (request.getAssignedVolunteer() == null || !request.getAssignedVolunteer().getId().equals(actor.getId())) {
                return ResponseEntity.status(403).build();
            }

            Tracking last = trackingRepository.findFirstByRequestIdAndVolunteerIdOrderByTimestampDesc(requestId, actor.getId());
            if (last != null && last.getTimestamp() != null) {
                LocalDateTime cutoff = LocalDateTime.now().minus(MIN_PUSH_INTERVAL);
                if (last.getTimestamp().isAfter(cutoff)) {
                    // Too frequent; just broadcast the existing latest point and return it.
                    messagingTemplate.convertAndSend("/topic/tracking/" + requestId, last);
                    return ResponseEntity.ok(last);
                }
            }

            Tracking tracking = new Tracking();
            tracking.setRequest(request);
            tracking.setVolunteer(actor);
            tracking.setLatitude(latitude);
            tracking.setLongitude(longitude);

            tracking = trackingRepository.save(tracking);

            // Send to WebSocket subscribers
            messagingTemplate.convertAndSend("/topic/tracking/" + requestId, tracking);

            return ResponseEntity.ok(tracking);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/request/{requestId}")
    public ResponseEntity<List<Tracking>> getTrackingByRequest(@PathVariable Long requestId) {
        return ResponseEntity.ok(trackingRepository.findByRequestIdOrderByTimestampDesc(requestId));
    }

    @GetMapping("/request/{requestId}/latest")
    public ResponseEntity<Tracking> getLatestTracking(@PathVariable Long requestId) {
        Tracking tracking = trackingRepository.findFirstByRequestIdOrderByTimestampDesc(requestId);
        if (tracking != null) {
            return ResponseEntity.ok(tracking);
        }
        return ResponseEntity.notFound().build();
    }
}

