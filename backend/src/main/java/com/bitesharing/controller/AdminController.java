package com.bitesharing.controller;

import com.bitesharing.service.FraudDetectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final FraudDetectionService fraudDetectionService;

    @GetMapping("/flagged-users")
    public ResponseEntity<?> getFlaggedUsers() {
        return ResponseEntity.ok(fraudDetectionService.getFlaggedUsers());
    }
}
