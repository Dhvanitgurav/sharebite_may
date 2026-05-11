package com.bitesharing.controller;

import com.bitesharing.dto.ErrorResponse;
import com.bitesharing.dto.chatbot.ChatbotMessageRequest;
import com.bitesharing.dto.chatbot.ChatbotMessageResponse;
import com.bitesharing.service.ChatbotService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

/**
 * Authenticated help chat — answers are tailored using JWT role (HOTEL, NEEDY, …).
 */
@RestController
@RequestMapping("/api/chatbot")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:3000")
public class ChatbotController {

    private final ChatbotService chatbotService;

    @PostMapping("/message")
    public ResponseEntity<?> message(
            @RequestBody ChatbotMessageRequest request,
            Authentication authentication) {
        try {
            String role = "GUEST";
            if (authentication != null && authentication.isAuthenticated()) {
                role = authentication.getAuthorities().stream()
                        .findFirst()
                        .map(GrantedAuthority::getAuthority)
                        .map(a -> a.replace("ROLE_", ""))
                        .orElse("GUEST");
            }
            ChatbotMessageResponse response = chatbotService.answer(
                    request.getMessage(),
                    role,
                    false
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ErrorResponse("Chatbot error: " + e.getMessage(), "CHATBOT_ERROR"));
        }
    }
}
