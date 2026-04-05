package com.example.deepseek.memory.dto;

import java.time.LocalDateTime;

public record LongTermMemoryDto(
    long id,
    long profileId,
    String category,
    String key,
    String value,
    int priority,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
