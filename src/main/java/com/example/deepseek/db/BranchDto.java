package com.example.deepseek.db;

import java.time.LocalDateTime;

public record BranchDto(
    long id,
    long sessionId,
    String name,
    Long parentMessageId,
    LocalDateTime createdAt
) {
    public boolean isMain() {
        return parentMessageId == null;
    }
}
