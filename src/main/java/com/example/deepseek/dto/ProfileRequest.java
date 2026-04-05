package com.example.deepseek.dto;

public record ProfileRequest(
    String name,
    String description,
    String systemPrompt,
    String personalization
) {
}
