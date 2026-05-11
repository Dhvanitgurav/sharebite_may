package com.bitesharing.controller;

import com.bitesharing.dto.ErrorResponse;
import com.bitesharing.dto.chatbot.ChatbotMessageRequest;
import com.bitesharing.dto.chatbot.ChatbotMessageResponse;
import com.bitesharing.service.ChatbotService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Public help chat (no login) — only topics marked public in {@code knowledge.json}.
 */
@RestController
@RequestMapping("/api/public/chatbot")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:3000")
public class ChatbotPublicController {

    private final ChatbotService chatbotService;

    @PostMapping("/message")
    public ResponseEntity<?> message(@RequestBody ChatbotMessageRequest request) {
        try {
            ChatbotMessageResponse response = chatbotService.answer(
                    request.getMessage(),
                    "GUEST",
                    true
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ErrorResponse("Chatbot error: " + e.getMessage(), "CHATBOT_ERROR"));
        }
    }
}
