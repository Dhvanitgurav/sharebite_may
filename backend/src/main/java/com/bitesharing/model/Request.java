package com.bitesharing.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "requests")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Request {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "donation_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "donor"})
    private Donation donation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "password"})
    private User requester;

    @Enumerated(EnumType.STRING)
    @Column(name = "requester_type", nullable = false)
    private RequesterType requesterType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestStatus status = RequestStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_volunteer_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "password"})
    private User assignedVolunteer;

    @Column(name = "pickup_address", columnDefinition = "TEXT")
    private String pickupAddress;

    @Column(name = "delivery_address", columnDefinition = "TEXT")
    private String deliveryAddress;

    @Column(name = "requester_phone", length = 30)
    private String requesterPhone;

    @Column(name = "qr_token", length = 80, nullable = false)
    private String qrToken;

    @Column(name = "pickup_qr_verified", nullable = false)
    private boolean pickupQrVerified = false;

    @Column(name = "delivery_qr_verified", nullable = false)
    private boolean deliveryQrVerified = false;

    @Column(name = "created_at", updatable = false)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    @Column(name = "delivery_proof_url", length = 500)
    private String deliveryProofUrl;

    @Column(name = "delivery_proof_note", columnDefinition = "TEXT")
    private String deliveryProofNote;

    @Column(name = "delivered_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime deliveredAt;

    @Column(name = "compost_proof_url", length = 500)
    private String compostProofUrl;

    @Column(name = "compost_proof_note", columnDefinition = "TEXT")
    private String compostProofNote;

    @Column(name = "composted_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime compostedAt;

    /**
     * SHA-256 handoff digest recorded when pickup QR is verified — audit / chain-of-custody (requestId|token|timestamp).
     */
    @Column(name = "handoff_attestation", length = 64)
    private String handoffAttestation;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (qrToken == null || qrToken.isBlank()) {
            qrToken = UUID.randomUUID().toString();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum RequesterType {
        NGO, VOLUNTEER, NEEDY, COMPOST_AGENCY
    }

    public enum RequestStatus {
        PENDING,
        ACCEPTED,
        ASSIGNED,
        PICKED_UP,
        DELIVERED,
        COMPLETED,
        CANCELLED,
        REJECTED,
        COMPOSTED,
        COMPOST_PICKED_UP,
        COMPOSTING
    }
}

