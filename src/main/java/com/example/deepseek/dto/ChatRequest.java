package com.example.deepseek.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatRequest(
        String model,
        List<Message> messages,
        Integer max_tokens,
        List<String> stop,
        Double temperature,
        Map<String, String> thinking,
        List<ToolDto> tools
) {

    public ChatRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Model cannot be null or blank");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("Messages cannot be null or empty");
        }
    }

    public ChatRequest(String model, List<Message> messages) {
        this(model, messages, null, null, null, null, null);
    }
    
    public ChatRequest(String model, List<Message> messages, Integer max_tokens, List<String> stop) {
        this(model, messages, max_tokens, stop, null, null, null);
    }
    
    public ChatRequest(String model, List<Message> messages, Integer max_tokens, List<String> stop, Double temperature, Map<String, String> thinking) {
        this(model, messages, max_tokens, stop, temperature, thinking, null);
    }
    
    public ChatRequest(String model, List<Message> messages, List<ToolDto> tools) {
        this(model, messages, null, null, null, null, tools);
    }
}
