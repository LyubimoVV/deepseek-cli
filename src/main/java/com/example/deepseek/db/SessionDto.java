package com.example.deepseek.db;

import java.time.LocalDateTime;

public record SessionDto(
    long id,
    String title,
    String model,
    String systemMessage,
    int mode,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    int messageCount
) {
}
