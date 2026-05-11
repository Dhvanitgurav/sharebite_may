package com.bitesharing.dto.chatbot;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ChatbotMessageResponse {
    private String reply;
    private List<ChatbotMatchDto> matches;
    /** "faq" | "faq+ollama" | "ollama" | "fallback" */
    private String sourceMode;
    private List<String> suggestedOpeners;
}
