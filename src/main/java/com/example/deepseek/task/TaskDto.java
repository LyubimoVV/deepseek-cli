package com.example.deepseek.task;

import java.time.LocalDateTime;

public record TaskDto(
    long id,
    long sessionId,
    String title,
    String description,
    TaskState state,
    String expectedAction,
    boolean paused,
    String pauseReason,
    String context,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
