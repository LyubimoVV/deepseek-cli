package com.example.deepseek.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Запрос к DeepSeek Chat API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatRequest(
        String model,
        List<Message> messages,
        Integer max_tokens,
        List<String> stop
) {

    public ChatRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Model cannot be null or blank");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("Messages cannot be null or empty");
        }
    }

    // Конструктор без ограничений (для обратной совместимости)
    public ChatRequest(String model, List<Message> messages) {
        this(model, messages, null, null);
    }
}
