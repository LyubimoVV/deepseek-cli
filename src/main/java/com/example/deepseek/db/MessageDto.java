package com.example.deepseek.db;

import java.time.LocalDateTime;

public record MessageDto(
    long id,
    long sessionId,
    String role,
    String content,
    int inputTokens,
    int outputTokens,
    int latency,
    double cost,
    LocalDateTime createdAt
) {
}
