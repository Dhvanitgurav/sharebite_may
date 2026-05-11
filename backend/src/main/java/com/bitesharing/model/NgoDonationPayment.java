package com.bitesharing.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ngo_donation_payments")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NgoDonationPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "ngo_id", nullable = false)
    private Long ngoId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "stripe_session_id", length = 200)
    private String stripeSessionId;

    @Column(name = "stripe_payment_intent_id", length = 200)
    private String stripePaymentIntentId;

    @Column(name = "payment_status", length = 40)
    private String paymentStatus;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
