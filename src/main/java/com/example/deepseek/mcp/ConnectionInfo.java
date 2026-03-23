package com.example.deepseek.mcp;

import com.example.deepseek.mcp.dto.McpServerConfig;

import java.time.Instant;

public record ConnectionInfo(
    String name,
    McpServerConfig config,
    Status status,
    String serverName,
    String serverVersion,
    Instant connectedAt,
    String lastError
) {
    public enum Status {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }
}
