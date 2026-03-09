package com.example.deepseek.db;

import java.time.LocalDateTime;

public record FactDto(
    long id,
    long sessionId,
    String category,
    String key,
    String value,
    LocalDateTime updatedAt
) {}
