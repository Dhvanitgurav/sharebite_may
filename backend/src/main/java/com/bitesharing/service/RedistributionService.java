package com.bitesharing.service;

import com.bitesharing.model.Donation;
import com.bitesharing.model.User;
import com.bitesharing.repository.DonationRepository;
import com.bitesharing.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedistributionService {

    private final DonationRepository donationRepository;
    private final UserRepository userRepository;

    @Scheduled(fixedDelay = 300000)
    @Transactional
    public void processRedistribution() {
        List<Donation> pending = donationRepository.findByStatus(Donation.DonationStatus.PENDING);
        LocalDateTime now = LocalDateTime.now();
        for (Donation donation : pending) {
            long minutes = Duration.between(donation.getCreatedAt(), now).toMinutes();
            if (minutes >= 60) {
                donation.setDonationType(Donation.DonationType.COMPOST);
                donation.setNgoNotificationRound(2);
                donation.setLastRedistributionAt(now);
                donationRepository.save(donation);
                notifyCompostAgencies(donation);
            } else if (minutes >= 30 && (donation.getNgoNotificationRound() == null || donation.getNgoNotificationRound() < 1)) {
                donation.setNgoNotificationRound(1);
                donation.setLastRedistributionAt(now);
                donationRepository.save(donation);
                notifyNextNgo(donation);
            }
        }
    }

    private void notifyNextNgo(Donation donation) {
        List<User> ngos = userRepository.findByUserTypeAndStatus(User.UserType.NGO, User.UserStatus.APPROVED);
        if (!ngos.isEmpty()) {
            log.info("Redistribution round 1 for donation {} to NGO {}", donation.getId(), ngos.get(0).getId());
        }
    }

    private void notifyCompostAgencies(Donation donation) {
        List<User> compostAgencies = userRepository.findByUserTypeAndStatus(User.UserType.COMPOST_AGENCY, User.UserStatus.APPROVED);
        if (!compostAgencies.isEmpty()) {
            log.info("Redistribution round 2 for donation {} to compost agency {}", donation.getId(), compostAgencies.get(0).getId());
        }
    }
}
