package com.example.deepseek.memory.dto;

import java.time.LocalDateTime;

public record WorkingMemoryDto(
    long id,
    long sessionId,
    String category,
    String key,
    String value,
    int priority,
    LocalDateTime updatedAt
) {
}
