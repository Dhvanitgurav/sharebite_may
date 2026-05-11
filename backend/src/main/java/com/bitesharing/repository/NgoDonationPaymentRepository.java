package com.bitesharing.repository;

import com.bitesharing.model.NgoDonationPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NgoDonationPaymentRepository extends JpaRepository<NgoDonationPayment, Long> {
    Optional<NgoDonationPayment> findByStripeSessionId(String stripeSessionId);
}
