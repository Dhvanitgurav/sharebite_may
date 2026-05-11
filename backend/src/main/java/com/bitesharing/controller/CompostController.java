package com.bitesharing.controller;

import com.bitesharing.model.User;
import com.bitesharing.repository.UserRepository;
import com.bitesharing.service.CompostService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/compost")
@RequiredArgsConstructor
public class CompostController {

    private final CompostService compostService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<?> getCompostRequests(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        User user = userRepository.findByUsername(authentication.getName()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(compostService.getCompostRequestsForUser(user.getId()));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable Long id,
            @RequestParam String status,
            @RequestParam(required = false) String proofUrl) {
        return ResponseEntity.ok(compostService.updateCompostStatus(id, status, proofUrl));
    }
}
