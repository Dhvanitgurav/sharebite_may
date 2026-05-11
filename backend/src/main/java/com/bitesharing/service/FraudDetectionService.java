package com.bitesharing.service;

import com.bitesharing.model.Donation;
import com.bitesharing.model.Request;
import com.bitesharing.model.User;
import com.bitesharing.repository.DonationRepository;
import com.bitesharing.repository.RequestRepository;
import com.bitesharing.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FraudDetectionService {

    private final UserRepository userRepository;
    private final DonationRepository donationRepository;
    private final RequestRepository requestRepository;

    @Transactional
    public void evaluateHotelRisk(Long hotelId) {
        long cancelledCount = donationRepository.findByDonorId(hotelId).stream()
                .filter(d -> d.getStatus() == Donation.DonationStatus.EXPIRED)
                .count();
        if (cancelledCount > 5) {
            userRepository.findById(hotelId).ifPresent(user -> {
                user.setFlagged(true);
                userRepository.save(user);
            });
        }
    }

    @Transactional
    public void evaluateDeliveryProofRisk(Long requesterId) {
        long deliveredWithoutProof = requestRepository.findByRequesterId(requesterId).stream()
                .filter(r -> r.getStatus() == Request.RequestStatus.DELIVERED)
                .filter(r -> r.getDeliveryProofUrl() == null || r.getDeliveryProofUrl().isBlank())
                .count();
        if (deliveredWithoutProof > 0) {
            userRepository.findById(requesterId).ifPresent(user -> {
                user.setFlagged(true);
                userRepository.save(user);
            });
        }
    }

    public List<User> getFlaggedUsers() {
        return userRepository.findByIsFlaggedTrue();
    }
}
