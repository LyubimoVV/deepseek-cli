package com.example.deepseek.memory.dto;

import java.time.LocalDateTime;

public record ProfileDto(
    long id,
    String name,
    String description,
    String systemPrompt,
    String settings,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
