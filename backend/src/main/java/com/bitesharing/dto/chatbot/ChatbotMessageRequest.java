package com.bitesharing.dto.chatbot;

import lombok.Data;

@Data
public class ChatbotMessageRequest {
    /** User question in natural language */
    private String message;
}
