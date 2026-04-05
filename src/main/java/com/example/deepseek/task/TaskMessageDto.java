package com.example.deepseek.task;

import java.time.LocalDateTime;

public record TaskMessageDto(
    long id,
    long taskId,
    TaskState taskState,
    String prompt,
    String response,
    int tokensUsed,
    Integer stepIndex,
    LocalDateTime createdAt
) {}
