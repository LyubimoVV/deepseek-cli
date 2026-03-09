package com.example.deepseek.db;

import java.time.LocalDateTime;

import com.example.deepseek.context.ContextStrategy;

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
    ContextStrategy contextStrategy,
    int windowSize
) {
}
