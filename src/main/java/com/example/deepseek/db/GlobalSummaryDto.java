package com.example.deepseek.db;

import java.time.LocalDateTime;

public record GlobalSummaryDto(
    long sessionId,
    String content,
    int version,
    Long lastMessageId,
    LocalDateTime updatedAt,
    Integer inputTokens,
    Integer outputTokens,
    Integer totalTokens,
    Double cost
) {}
