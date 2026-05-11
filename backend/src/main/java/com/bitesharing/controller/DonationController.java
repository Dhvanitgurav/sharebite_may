package com.bitesharing.controller;

import com.bitesharing.dto.ErrorResponse;
import com.bitesharing.model.Donation;
import com.bitesharing.model.User;
import com.bitesharing.repository.UserRepository;
import com.bitesharing.service.DonationService;
import com.bitesharing.service.DonorDonationsPdfExportService;
import com.bitesharing.service.MatchingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/donations")
@RequiredArgsConstructor
public class DonationController {

    private final DonationService donationService;
    private final MatchingService matchingService;
    private final UserRepository userRepository;
    private final DonorDonationsPdfExportService donorDonationsPdfExportService;

    @PostMapping
    public ResponseEntity<?> createDonation(
            @RequestBody Donation donation,
            @RequestParam Long donorId,
            HttpServletRequest request // <-- ADD THIS
    ) {
        try {

            System.out.println("======= DONATION CONTROLLER DEBUG =======");
            System.out.println("Authorization header received:");
            System.out.println(request.getHeader("Authorization"));
            System.out.println("========================================");

            return ResponseEntity.ok(donationService.createDonation(donation, donorId));

        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Donation error";
            String code = msg.startsWith("ML_SAFETY_BLOCK") ? "ML_SAFETY_BLOCK" : "DONATION_ERROR";
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse(msg, code));
        } catch (Exception e) {
            log.error("Error creating donation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("An error occurred while creating donation: " + e.getMessage(), "INTERNAL_ERROR"));
        }
    }


    @GetMapping
    public ResponseEntity<?> getAllDonations() {
        try {
            List<Donation> donations = donationService.getAllDonations();
            log.debug("Retrieved {} donations", donations.size());
            return ResponseEntity.ok(donations);
        } catch (DataAccessException e) {
            log.error("Database error fetching donations: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("Database connection error. Please check your database configuration.", "DATABASE_ERROR"));
        } catch (Exception e) {
            log.error("Error fetching donations: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("An error occurred while fetching donations: " + e.getMessage(), "INTERNAL_ERROR"));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getDonationById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(donationService.getDonationById(id));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(e.getMessage(), "DONATION_NOT_FOUND"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("An error occurred while fetching donation", "INTERNAL_ERROR"));
        }
    }

    @GetMapping("/donor/{donorId}")
    public ResponseEntity<List<Donation>> getDonationsByDonor(@PathVariable Long donorId) {
        try {
            return ResponseEntity.ok(donationService.getDonationsByDonor(donorId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Hotel / donor: download a PDF listing of their donations. Admins may export any donor's list.
     */
    @GetMapping("/donor/{donorId}/export/pdf")
    public ResponseEntity<?> exportDonorDonationsPdf(
            @PathVariable Long donorId,
            Authentication authentication) {
        try {
            if (authentication == null || authentication.getName() == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorResponse("Authentication required", "UNAUTHORIZED"));
            }
            User actor = userRepository.findByUsername(authentication.getName())
                    .orElseThrow(() -> new RuntimeException("User not found"));
            boolean isAdmin = actor.getUserType() == User.UserType.ADMIN;
            if (!isAdmin && !actor.getId().equals(donorId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new ErrorResponse("You can only download your own donations.", "FORBIDDEN"));
            }
            User donorForHeader = actor.getId().equals(donorId)
                    ? actor
                    : userRepository.findById(donorId).orElseThrow(() -> new RuntimeException("Donor not found"));
            byte[] pdf = donorDonationsPdfExportService.buildPdf(
                    donorForHeader,
                    donationService.getDonationsByDonor(donorId));
            String filename = "sharebite-donations-" + donorId + ".pdf";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);
        } catch (RuntimeException e) {
            log.warn("Donor PDF export: {}", e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage(), "PDF_EXPORT_ERROR"));
        } catch (Exception e) {
            log.error("Donor PDF export failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Could not build PDF: " + e.getMessage(), "INTERNAL_ERROR"));
        }
    }

    @GetMapping("/type/{type}")
    public ResponseEntity<?> getDonationsByType(@PathVariable String type) {
        try {
            return ResponseEntity.ok(donationService.getDonationsByType(
                    Donation.DonationType.valueOf(type.toUpperCase())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Invalid donation type: " + type, "INVALID_TYPE"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("An error occurred while fetching donations", "INTERNAL_ERROR"));
        }
    }

    @GetMapping("/available/{type}")
    public ResponseEntity<?> getAvailableDonations(@PathVariable String type) {
        try {
            return ResponseEntity.ok(donationService.getAvailableDonations(
                    Donation.DonationType.valueOf(type.toUpperCase())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Invalid donation type: " + type, "INVALID_TYPE"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("An error occurred while fetching available donations", "INTERNAL_ERROR"));
        }
    }

    @GetMapping("/available/{type}/prioritized")
    public ResponseEntity<?> getPrioritizedAvailableDonations(
            @PathVariable String type,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude) {
        try {
            return ResponseEntity.ok(donationService.getPrioritizedAvailableDonations(
                    Donation.DonationType.valueOf(type.toUpperCase()),
                    latitude,
                    longitude
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Invalid donation type: " + type, "INVALID_TYPE"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("An error occurred while fetching prioritized donations", "INTERNAL_ERROR"));
        }
    }

    @GetMapping("/smart-match")
    public ResponseEntity<?> getSmartMatches(@RequestParam Long ngoId) {
        try {
            User ngo = userRepository.findById(ngoId)
                    .orElseThrow(() -> new RuntimeException("NGO not found"));
            return ResponseEntity.ok(matchingService.getSmartMatchesForNgo(ngo));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage(), "SMART_MATCH_ERROR"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Failed to fetch smart matches", "INTERNAL_ERROR"));
        }
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateDonationStatus(
            @PathVariable Long id,
            @RequestParam String status) {
        try {
            Donation.DonationStatus donationStatus = Donation.DonationStatus.valueOf(status.toUpperCase());
            return ResponseEntity.ok(donationService.updateDonationStatus(id, donationStatus));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Invalid status: " + status, "INVALID_STATUS"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(e.getMessage(), "DONATION_NOT_FOUND"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("An error occurred while updating donation status", "INTERNAL_ERROR"));
        }
    }
}

