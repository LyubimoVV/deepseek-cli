package com.example.deepseek.db;

import java.time.LocalDateTime;

public record SummaryDto(
    long id,
    long sessionId,
    String content,
    Integer messageRangeStart,
    Integer messageRangeEnd,
    LocalDateTime createdAt
) {}
