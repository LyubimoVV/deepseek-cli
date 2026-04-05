package com.example.deepseek.db;

import java.time.LocalDateTime;

public record SessionHeartbeatDto(
    long id,
    long sessionId,
    LocalDateTime lastHeartbeat,
    LocalDateTime updatedAt
) {}
