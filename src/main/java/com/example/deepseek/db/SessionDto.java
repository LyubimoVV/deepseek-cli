package com.example.deepseek.db;

import java.time.LocalDateTime;

public record SessionDto(
    long id,
    String title,
    String model,
    String systemMessage,
    int mode,
    int totalTokens,
    double totalCost,
    int requestCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    int messageCount,
    int keepMessagesCount,
    int summaryInterval,
    boolean summaryEnabled
) {
}
