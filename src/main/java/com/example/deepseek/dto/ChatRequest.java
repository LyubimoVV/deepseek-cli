package com.example.deepseek.dto;

import java.util.List;

/**
 * Запрос к DeepSeek Chat API.
 */
public record ChatRequest(
        String model,
        List<Message> messages
) {
    
    public ChatRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Model cannot be null or blank");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("Messages cannot be null or empty");
        }
    }
}
