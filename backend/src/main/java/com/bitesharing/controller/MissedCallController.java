package com.bitesharing.controller;

import com.bitesharing.model.Request;
import com.bitesharing.service.RequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MissedCallController {

    private final RequestService requestService;

    @PostMapping("/missed-call")
    public ResponseEntity<?> createByMissedCall(@RequestParam(required = false) Long donationId) {
        try {
            Request created = requestService.createRequestFromSignal(
                    donationId,
                    Request.RequesterType.NEEDY,
                    "MISSED-CALL"
            );
            return ResponseEntity.ok(created);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }
}
