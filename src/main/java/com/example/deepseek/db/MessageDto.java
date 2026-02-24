package com.example.deepseek.db;

import java.time.LocalDateTime;

public record MessageDto(
    long id,
    long sessionId,
    String role,
    String content,
    LocalDateTime createdAt
) {
}
