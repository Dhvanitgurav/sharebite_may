package com.bitesharing.service;

import com.bitesharing.dto.FoodFreshnessResult;
import com.bitesharing.model.Donation;
import com.bitesharing.model.PointsHistory;
import com.bitesharing.model.User;
import com.bitesharing.repository.DonationRepository;
import com.bitesharing.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DonationService {

    private final DonationRepository donationRepository;
    private final UserRepository userRepository;
    private final GamificationService gamificationService;
    private final FoodFreshnessService foodFreshnessService;
    private final ExpiryRiskService expiryRiskService;
    private final FraudDetectionService fraudDetectionService;
    private final SmsIntakeService smsIntakeService;

    @Value("${file.upload.dir:./uploads}")
    private String uploadDir;

    @Value("${freshness.publish.require-successful-ml:false}")
    private boolean requireSuccessfulMl;

    @Value("${freshness.client-model-label:food-freshness-v1}")
    private String freshnessClientModelLabel;

    @Transactional
    public Donation createDonation(Donation donation, Long donorId) {
        User donor = userRepository.findById(donorId)
                .orElseThrow(() -> new RuntimeException("Donor not found"));

        if (donation.getMlPolicyOverrideReason() != null) {
            String trimmed = donation.getMlPolicyOverrideReason().trim();
            donation.setMlPolicyOverrideReason(trimmed.isEmpty() ? null : trimmed);
        }

        donation.setDonor(donor);
        donation.setStatus(Donation.DonationStatus.AVAILABLE);
        if (donation.getExpiryDate() == null) {
            donation.setExpiryDate(LocalDateTime.now().plusHours(expiryRiskService.getDefaultShelfLifeHours(donation.getFoodName())));
        }

        applyServerSideFreshness(donation);
        notifyFreshnessRiskIfNeeded(donor, donation);
        enforceMlConsumptionSafety(donation);

        Donation savedDonation = donationRepository.save(donation);

        gamificationService.addPoints(donorId, 10, "Created food donation: " + donation.getFoodName(),
                PointsHistory.RelatedEntityType.DONATION, savedDonation.getId());

        return savedDonation;
    }

    /**
     * Re-run CNN on the uploaded image stored under {@code file.upload.dir} so stored {@code ml*} fields
     * match the server (cannot be spoofed from the browser).
     */
    private void applyServerSideFreshness(Donation donation) {
        String filename = extractUploadedFilename(donation.getPhotoUrl());
        if (filename == null) {
            return;
        }
        Path base = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path file = base.resolve(filename).normalize();
        if (!file.startsWith(base)) {
            log.warn("Rejected photo path outside upload dir: {}", filename);
            if (requireSuccessfulMl) {
                throw new RuntimeException("Invalid photo path for server AI check.");
            }
            return;
        }
        if (!Files.isRegularFile(file)) {
            log.warn("Photo file not found for server AI: {}", file);
            clearFreshnessFields(donation);
            if (requireSuccessfulMl) {
                throw new RuntimeException(
                        "Photo file not found on server; cannot run AI. Upload again or set freshness.publish.require-successful-ml=false.");
            }
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            FoodFreshnessResult result = foodFreshnessService.analyze(bytes, filename);
            result.setModelUsed(freshnessClientModelLabel);
            donation.setFreshnessStatus(result.getStatus());
            donation.setFreshnessConfidence(result.getConfidence());
            donation.setFreshnessModelUsed(result.getModelUsed());
            donation.setFreshnessProcessingTimeMs(result.getProcessingTimeMs());
            donation.setFreshnessMessage(result.getMessage());
            donation.setMlFreshnessStatus(result.getStatus());
            donation.setMlFreshnessConfidence(result.getConfidence());
            donation.setMlFreshnessMessage(result.getMessage());
            donation.setMlEstimatedExpiryHours(result.getEstimatedExpiryHours());
            log.info("Server-side freshness analysis for donation photo {}: {} ({}) using {}", filename, result.getStatus(), result.getConfidence(), result.getModelUsed());
        } catch (Exception e) {

            e.printStackTrace();

            Throwable root = e;

            while (root.getCause() != null) {
                root = root.getCause();
            }

            log.error("REAL ERROR: ", root);

            throw new RuntimeException(root.getMessage());
        }
    }

    private static void clearFreshnessFields(Donation donation) {
        donation.setFreshnessStatus(null);
        donation.setFreshnessConfidence(null);
        donation.setFreshnessModelUsed(null);
        donation.setFreshnessProcessingTimeMs(null);
        donation.setFreshnessMessage(null);
        donation.setMlFreshnessStatus(null);
        donation.setMlFreshnessConfidence(null);
        donation.setMlFreshnessMessage(null);
        donation.setMlEstimatedExpiryHours(null);
    }

    /**
     * Block listing clearly rotten food as human/dog consumption unless an audit reason is given or type is COMPOST.
     */
    private void enforceMlConsumptionSafety(Donation donation) {
        if (donation.getFreshnessStatus() == null) {
            return;
        }
        if (!"ROTTEN".equalsIgnoreCase(donation.getFreshnessStatus())) {
            return;
        }
        Double c = donation.getFreshnessConfidence();
        if (c == null || c < 0.6) {
            return;
        }
        Donation.DonationType t = donation.getDonationType();
        if (t != Donation.DonationType.HUMAN && t != Donation.DonationType.DOG) {
            return;
        }
        String reason = donation.getMlPolicyOverrideReason();
        if (reason == null || reason.length() < 15) {
            throw new RuntimeException(
                    "ML_SAFETY_BLOCK: Server AI classifies this photo as ROTTEN with high confidence. "
                            + "Change donation type to COMPOST, or set mlPolicyOverrideReason (min 15 characters) "
                            + "confirming you accept responsibility for listing as "
                            + t.name().toLowerCase(Locale.ROOT) + " consumption.");
        }
    }

    private void notifyFreshnessRiskIfNeeded(User donor, Donation donation) {
        if (donor == null || donation == null) return;
        if (!"ROTTEN".equalsIgnoreCase(donation.getFreshnessStatus())) return;
        Double c = donation.getFreshnessConfidence();
        if (c == null || c < 0.6) return;
        smsIntakeService.sendFreshnessRiskSms(
                donor.getPhone(),
                "ShareBite alert: AI detected spoilage risk for your upload. Please review and route to compost if needed."
        );
    }

    /**
     * Accepts URLs like {@code http://host:8080/api/files/uuid.jpg} or {@code /api/files/uuid.jpg}.
     */
    private String extractUploadedFilename(String photoUrl) {
        if (photoUrl == null || photoUrl.isBlank()) {
            return null;
        }
        if (!photoUrl.contains("/api/files/")) {
            return null;
        }
        int idx = photoUrl.lastIndexOf('/');
        if (idx < 0 || idx >= photoUrl.length() - 1) {
            return null;
        }
        String name = photoUrl.substring(idx + 1);
        int q = name.indexOf('?');
        if (q > 0) {
            name = name.substring(0, q);
        }
        if (!name.matches("[a-zA-Z0-9._-]+")) {
            return null;
        }
        return name;
    }

    public List<Donation> getAllDonations() {
        return donationRepository.findAll();
    }

    public List<Donation> getDonationsByDonor(Long donorId) {
        return donationRepository.findByDonorId(donorId);
    }

    public List<Donation> getDonationsByType(Donation.DonationType type) {
        return donationRepository.findByDonationType(type);
    }

    public List<Donation> getAvailableDonations(Donation.DonationType type) {
        return donationRepository.findByDonationTypeAndStatusIn(type, List.of(
                Donation.DonationStatus.AVAILABLE,
                Donation.DonationStatus.PENDING
        ));
    }

    public List<Map<String, Object>> getPrioritizedAvailableDonations(
            Donation.DonationType type,
            Double latitude,
            Double longitude
    ) {
        List<Donation> available = getAvailableDonations(type);
        List<Map<String, Object>> scored = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (Donation donation : available) {
            double score = 0.0;
            List<String> reasons = new ArrayList<>();

            if (donation.getExpiryDate() != null) {
                long mins = ChronoUnit.MINUTES.between(now, donation.getExpiryDate());
                if (mins <= 120) {
                    score += 55;
                    reasons.add("Expires within 2 hours");
                } else if (mins <= 360) {
                    score += 35;
                    reasons.add("Expires within 6 hours");
                } else if (mins <= 720) {
                    score += 20;
                    reasons.add("Expires within 12 hours");
                }
            }

            if (donation.getQuantity() != null) {
                if (donation.getQuantity() >= 30) {
                    score += 18;
                    reasons.add("Large batch size");
                } else if (donation.getQuantity() >= 10) {
                    score += 10;
                }
            }

            if (latitude != null && longitude != null && donation.getLatitude() != null && donation.getLongitude() != null) {
                double distanceKm = distanceKm(latitude, longitude, donation.getLatitude(), donation.getLongitude());
                if (distanceKm <= 2) {
                    score += 20;
                    reasons.add("Very close pickup distance");
                } else if (distanceKm <= 5) {
                    score += 12;
                } else if (distanceKm <= 12) {
                    score += 5;
                }
            }

            if ("ROTTEN".equalsIgnoreCase(donation.getMlFreshnessStatus())) {
                score += 8;
                reasons.add("AI flagged urgent handling");
            }

            Map<String, Object> item = new HashMap<>();
            item.put("donation", donation);
            item.put("urgencyScore", Math.round(Math.min(100.0, score) * 10.0) / 10.0);
            item.put("reasons", reasons);
            scored.add(item);
        }

        scored.sort(Comparator.comparingDouble((Map<String, Object> m) -> (Double) m.get("urgencyScore")).reversed());
        return scored;
    }

    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return 6371 * c;
    }

    public Donation getDonationById(Long id) {
        return donationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Donation not found"));
    }

    @Transactional
    public Donation updateDonationStatus(Long id, Donation.DonationStatus status) {
        Donation donation = getDonationById(id);
        donation.setStatus(status);
        return donationRepository.save(donation);
    }

    @Transactional
    public void checkAndMarkExpiredDonations() {
        LocalDateTime now = LocalDateTime.now();
        List<Donation> expiredDonations = donationRepository.findByStatusIn(List.of(
                Donation.DonationStatus.AVAILABLE,
                Donation.DonationStatus.PENDING
        ));
        expiredDonations.stream()
                .filter(donation -> donation.getExpiryDate() != null && donation.getExpiryDate().isBefore(now))
                .forEach(donation -> {
                    donation.setStatus(Donation.DonationStatus.EXPIRED);
                    donationRepository.save(donation);
                    fraudDetectionService.evaluateHotelRisk(donation.getDonor().getId());
                });
    }
}
