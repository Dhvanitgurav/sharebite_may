package com.bitesharing.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "donations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Donation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "donor_id", nullable = false)
    @JsonIgnoreProperties({
            "hibernateLazyInitializer",
            "handler",
            "password",
            "userPoints",
            "donations"
    })  // ✅ FIX 2 — ignore lazy fields inside donor
    private User donor;

    @Column(name = "food_name", nullable = false, length = 200)
    private String foodName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "expiry_date", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime expiryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "donation_type", nullable = false)
    private DonationType donationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DonationStatus status = DonationStatus.AVAILABLE;

    @Column(columnDefinition = "TEXT")
    private String photoUrl;

    /** Last AI freshness check at donation time (from Python CNN via Spring proxy). */
    @Column(name = "ml_freshness_status", length = 20)
    private String mlFreshnessStatus;

    @Column(name = "ml_freshness_confidence")
    private Double mlFreshnessConfidence;

    @Column(columnDefinition = "TEXT")
    private String mlFreshnessMessage;

    @Column(name = "freshness_status", length = 20)
    private String freshnessStatus;

    @Column(name = "freshness_confidence")
    private Double freshnessConfidence;

    @Column(name = "freshness_model_used", length = 50)
    private String freshnessModelUsed;

    @Column(name = "freshness_processing_time_ms")
    private Long freshnessProcessingTimeMs;

    @Column(name = "freshness_message", length = 500)
    private String freshnessMessage;

    @Column(name = "ml_estimated_expiry_hours")
    private Integer mlEstimatedExpiryHours;

    /**
     * If server AI flags ROTTEN for HUMAN/DOG listing, donor may supply a justification (audit).
     * Min length enforced in {@link com.bitesharing.service.DonationService}.
     */
    @Column(columnDefinition = "TEXT")
    private String mlPolicyOverrideReason;

    private Double latitude;

    private Double longitude;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(name = "created_at", updatable = false)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @Column(name = "last_redistribution_at")
    private LocalDateTime lastRedistributionAt;

    @Column(name = "ngo_notification_round")
    private Integer ngoNotificationRound = 0;

    @Column(name = "updated_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum DonationType {
        HUMAN, DOG, COMPOST
    }

    public enum DonationStatus {
        PENDING,
        AVAILABLE,
        ACCEPTED,
        ASSIGNED,
        PICKED_UP,
        DELIVERED,
        COMPOSTED,
        EXPIRED;

        public boolean isAvailable() {
            return this == AVAILABLE || this == PENDING || this == ACCEPTED;
        }
    }
}
