package com.bitesharing.service;

import com.bitesharing.model.Donation;
import com.bitesharing.model.PointsHistory;
import com.bitesharing.model.Request;
import com.bitesharing.model.User;
import com.bitesharing.repository.DonationRepository;
import com.bitesharing.repository.RequestRepository;
import com.bitesharing.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RequestService {

    private final RequestRepository requestRepository;
    private final DonationRepository donationRepository;
    private final UserRepository userRepository;
    private final GamificationService gamificationService;
    private final FraudDetectionService fraudDetectionService;

    @Transactional
    public Request createRequest(Long donationId, Long requesterId, Request.RequesterType requesterType) {
        Donation donation = null;
        if (donationId != null) {
            donation = donationRepository.findById(donationId)
                    .orElseThrow(() -> new RuntimeException("Donation not found"));
            assertDonationClaimable(donation);
        }

        User requester = null;
        if (requesterId != null) {
            requester = userRepository.findById(requesterId)
                    .orElseThrow(() -> new RuntimeException("Requester not found"));
        }

        Request request = new Request();
        request.setDonation(donation);
        request.setRequester(requester);
        request.setRequesterType(requesterType);
        request.setStatus(Request.RequestStatus.PENDING);
        request.setQrToken(UUID.randomUUID().toString());
        request.setPickupAddress(donation != null ? donation.getAddress() : null);
        if (requester != null && requester.getAddress() != null && !requester.getAddress().isBlank()) {
            request.setDeliveryAddress(requester.getAddress());
        } else if (donation != null) {
            request.setDeliveryAddress(donation.getAddress());
        }

        return requestRepository.save(request);
    }

    @Transactional
    public Request acceptRequest(Long requestId) {
        Request request = getRequestById(requestId);
        if (request.getStatus() != Request.RequestStatus.PENDING) {
            throw new RuntimeException("Only pending requests can be accepted");
        }
        request.setStatus(Request.RequestStatus.ACCEPTED);
        return requestRepository.save(request);
    }

    @Transactional
    public Request assignDonation(Long requestId, Long donationId) {
        Request request = getRequestById(requestId);
        if (request.getStatus() != Request.RequestStatus.ACCEPTED && request.getStatus() != Request.RequestStatus.PENDING) {
            throw new RuntimeException("Request must be accepted before assigning a donation");
        }
        Donation donation = donationRepository.findById(donationId)
                .orElseThrow(() -> new RuntimeException("Donation not found"));
        assertDonationClaimable(donation);
        request.setDonation(donation);
        request.setPickupAddress(donation.getAddress());
        donation.setStatus(Donation.DonationStatus.ASSIGNED);
        donationRepository.save(donation);
        request.setStatus(Request.RequestStatus.ASSIGNED);
        return requestRepository.save(request);
    }

    @Transactional
    public Request createRequestFromSignal(Long donationId, Request.RequesterType requesterType, String signal) {
        Donation donation = donationId == null
                ? donationRepository.findByStatus(Donation.DonationStatus.AVAILABLE).stream().findFirst()
                .orElse(null)
                : donationRepository.findById(donationId).orElseThrow(() -> new RuntimeException("Donation not found"));
        if (donation == null) {
            throw new RuntimeException("No pending donation available right now. Please try again soon.");
        }
        User ngo = userRepository.findByUserTypeAndStatus(User.UserType.NGO, User.UserStatus.APPROVED).stream()
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No NGO available"));
        Request request = new Request();
        request.setDonation(donation);
        request.setRequester(ngo);
        request.setRequesterType(requesterType);
        request.setStatus(Request.RequestStatus.ACCEPTED);
        request.setQrToken(UUID.randomUUID().toString());
        request.setPickupAddress(donation.getAddress());
        request.setDeliveryAddress(signal + " request");
        donation.setStatus(Donation.DonationStatus.ASSIGNED);
        donationRepository.save(donation);
        return requestRepository.save(request);
    }

    @Transactional
    public Request verifyQr(Long requestId, String token, String stage) {
        Request request = getRequestById(requestId);
        String t = token == null ? "" : token.trim();
        if (request.getQrToken() == null || !request.getQrToken().equals(t)) {
            throw new RuntimeException("Invalid QR token");
        }
        if ("PICKUP".equalsIgnoreCase(stage)) {
            if (request.getStatus() != Request.RequestStatus.ASSIGNED) {
                throw new RuntimeException("Pickup QR applies only when a volunteer is assigned (ASSIGNED).");
            }
            request.setPickupQrVerified(true);
            request.setStatus(Request.RequestStatus.PICKED_UP);
            request.setHandoffAttestation(computeHandoffAttestation(request.getId(), t));
        } else if ("DELIVERY".equalsIgnoreCase(stage)) {
            if (request.getStatus() != Request.RequestStatus.PICKED_UP) {
                throw new RuntimeException("Verify pickup first: delivery QR is valid only after PICKED_UP.");
            }
            request.setDeliveryQrVerified(true);
        } else {
            throw new RuntimeException("Unsupported QR stage");
        }
        return requestRepository.save(request);
    }

    private static void assertDonationClaimable(Donation donation) {
        Donation.DonationStatus s = donation.getStatus();
        if (s != Donation.DonationStatus.AVAILABLE && s != Donation.DonationStatus.PENDING) {
            throw new RuntimeException("Donation is not available");
        }
    }

    private static String computeHandoffAttestation(Long requestId, String qrToken) {
        try {
            String payload = requestId + "|" + qrToken + "|" + System.currentTimeMillis();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return null;
        }
    }

    public Map<String, Object> getProofFingerprint(Long requestId) {
        Request request = getRequestById(requestId);
        String payload = String.join("|",
                String.valueOf(request.getId()),
                Objects.toString(request.getQrToken(), ""),
                Objects.toString(request.getStatus(), ""),
                Objects.toString(request.getDeliveryProofUrl(), ""),
                Objects.toString(request.getCompostProofUrl(), ""),
                Objects.toString(request.getDeliveredAt(), ""),
                Objects.toString(request.getCompostedAt(), ""),
                Objects.toString(request.getHandoffAttestation(), "")
        );
        String fingerprint = computeSha256(payload);
        return Map.of(
                "requestId", request.getId(),
                "status", request.getStatus().name(),
                "fingerprint", fingerprint
        );
    }

    private static String computeSha256(String payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("Could not compute fingerprint");
        }
    }

    public List<Request> getAllRequests() {
        return requestRepository.findAll();
    }

    public List<Request> getRequestsByRequester(Long requesterId) {
        return requestRepository.findByRequesterId(requesterId);
    }

    public List<Request> getRequestsByVolunteer(Long volunteerId) {
        return requestRepository.findByAssignedVolunteerId(volunteerId);
    }

    public List<Request> getRequestsByDonation(Long donationId) {
        return requestRepository.findByDonationId(donationId);
    }

    public List<Request> getRequestsByStatus(Request.RequestStatus status) {
        return requestRepository.findByStatus(status);
    }

    public List<Request> getRequestsByDonor(Long donorId) {
        return requestRepository.findByDonationDonorId(donorId);
    }

    public Request getRequestById(Long id) {
        return requestRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Request not found"));
    }

    @Transactional
    public Request assignVolunteer(Long requestId, Long volunteerId) {
        Request request = requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new RuntimeException("Request not found"));
        User volunteer = userRepository.findById(volunteerId)
                .orElseThrow(() -> new RuntimeException("Volunteer not found"));

        if (volunteer.getUserType() != User.UserType.VOLUNTEER) {
            throw new RuntimeException("User is not a volunteer");
        }

        if (request.getStatus() != Request.RequestStatus.ACCEPTED && request.getStatus() != Request.RequestStatus.ASSIGNED) {
            throw new RuntimeException("Request must be accepted before assigning a volunteer");
        }

        if (request.getAssignedVolunteer() != null) {
            if (!request.getAssignedVolunteer().getId().equals(volunteerId)) {
                throw new RuntimeException("Request already assigned to another volunteer");
            }
            return request;
        }

        request.setAssignedVolunteer(volunteer);
        request.setStatus(Request.RequestStatus.ASSIGNED);
        return requestRepository.save(request);
    }

    @Transactional
    public Request updateRequestStatus(Long id, Request.RequestStatus status) {
        return updateRequestStatus(id, status, null, null, null, null);
    }

    @Transactional
    public Request updateRequestStatus(Long id, Request.RequestStatus status, String deliveryProofUrl, String deliveryProofNote) {
        return updateRequestStatus(id, status, deliveryProofUrl, deliveryProofNote, null, null);
    }

    @Transactional
    public Request updateRequestStatus(
            Long id,
            Request.RequestStatus status,
            String deliveryProofUrl,
            String deliveryProofNote,
            String compostProofUrl,
            String compostProofNote) {
        Request request = getRequestById(id);
        Request.RequestStatus oldStatus = request.getStatus();

        if (status == Request.RequestStatus.PICKED_UP) {
            if (request.getStatus() != Request.RequestStatus.ASSIGNED) {
                throw new RuntimeException("Only assigned requests can be marked picked up");
            }
            request.setStatus(status);
            if (request.getDonation() != null) {
                request.getDonation().setStatus(Donation.DonationStatus.PICKED_UP);
                donationRepository.save(request.getDonation());
            }
        } else if (status == Request.RequestStatus.DELIVERED) {
            if (request.getStatus() != Request.RequestStatus.PICKED_UP && request.getStatus() != Request.RequestStatus.ASSIGNED) {
                throw new RuntimeException("Only picked up requests can be marked delivered");
            }
            request.setStatus(status);
            request.setDeliveredAt(LocalDateTime.now());
            if (deliveryProofUrl != null && !deliveryProofUrl.isBlank()) {
                request.setDeliveryProofUrl(deliveryProofUrl);
            }
            if (deliveryProofNote != null && !deliveryProofNote.isBlank()) {
                request.setDeliveryProofNote(deliveryProofNote.trim());
            }
            if (request.getDonation() != null) {
                request.getDonation().setStatus(Donation.DonationStatus.DELIVERED);
                donationRepository.save(request.getDonation());
            }
        } else if (status == Request.RequestStatus.COMPLETED) {
            if (request.getStatus() != Request.RequestStatus.DELIVERED && request.getStatus() != Request.RequestStatus.COMPOSTED) {
                throw new RuntimeException("Only delivered or composted requests can be completed");
            }
            request.setStatus(status);
        } else if (status == Request.RequestStatus.CANCELLED) {
            request.setStatus(status);
            if (request.getDonation() != null && request.getDonation().getStatus() == Donation.DonationStatus.ASSIGNED) {
                request.getDonation().setStatus(Donation.DonationStatus.AVAILABLE);
                donationRepository.save(request.getDonation());
            }
        } else if (status == Request.RequestStatus.COMPOSTED) {
            if (compostProofUrl == null || compostProofUrl.isBlank()) {
                throw new RuntimeException("Compost proof image is required");
            }
            request.setStatus(status);
            request.setCompostedAt(LocalDateTime.now());
            request.setCompostProofUrl(compostProofUrl);
            if (compostProofNote != null && !compostProofNote.isBlank()) {
                request.setCompostProofNote(compostProofNote.trim());
            }
            if (request.getDonation() != null) {
                request.getDonation().setStatus(Donation.DonationStatus.COMPOSTED);
                donationRepository.save(request.getDonation());
            }
        } else {
            request.setStatus(status);
        }

        if (status == Request.RequestStatus.ASSIGNED && request.getDonation() != null && request.getDonation().getStatus() != Donation.DonationStatus.ASSIGNED) {
            request.getDonation().setStatus(Donation.DonationStatus.ASSIGNED);
            donationRepository.save(request.getDonation());
        }

        Request savedRequest = requestRepository.save(request);

        if (status == Request.RequestStatus.DELIVERED && oldStatus != Request.RequestStatus.DELIVERED) {
            if (savedRequest.getAssignedVolunteer() != null) {
                gamificationService.addPoints(savedRequest.getAssignedVolunteer().getId(), 15,
                        "Completed delivery for: " + savedRequest.getDonation().getFoodName(),
                        PointsHistory.RelatedEntityType.DELIVERY, savedRequest.getId());
            }
            if (savedRequest.getRequester() != null) {
                fraudDetectionService.evaluateDeliveryProofRisk(savedRequest.getRequester().getId());
            }
        }

        if (status == Request.RequestStatus.COMPLETED && oldStatus != Request.RequestStatus.COMPLETED) {
            if (savedRequest.getAssignedVolunteer() != null) {
                gamificationService.addPoints(savedRequest.getAssignedVolunteer().getId(), 10,
                        "Completed request: " + savedRequest.getDonation().getFoodName(),
                        PointsHistory.RelatedEntityType.DELIVERY, savedRequest.getId());
            }
        }

        if (status == Request.RequestStatus.COMPOSTED && oldStatus != Request.RequestStatus.COMPOSTED) {
            if (savedRequest.getRequester() != null) {
                gamificationService.addPoints(savedRequest.getRequester().getId(), 20,
                        "Composted: " + savedRequest.getDonation().getFoodName(),
                        PointsHistory.RelatedEntityType.COMPOST, savedRequest.getId());
            }
        }

        return savedRequest;
    }
}

