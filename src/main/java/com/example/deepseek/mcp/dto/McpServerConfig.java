package com.example.deepseek.mcp.dto;

import com.fasterxml.jackson.annotation.JsonCreator;

public record McpServerConfig(
    String url,
    String description
) {
    @JsonCreator
    public McpServerConfig {}

    public static McpServerConfig create(String url, String description) {
        return new McpServerConfig(url, description);
    }
}
