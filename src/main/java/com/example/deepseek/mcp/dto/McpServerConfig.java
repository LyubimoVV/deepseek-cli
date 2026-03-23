package com.example.deepseek.mcp.dto;

import com.fasterxml.jackson.annotation.JsonCreator;

public record McpServerConfig(
    String url,
    String description,
    String apiKey
) {
    @JsonCreator
    public McpServerConfig {}

    public McpServerConfig(String url, String description) {
        this(url, description, null);
    }

    public static McpServerConfig create(String url, String description) {
        return new McpServerConfig(url, description, null);
    }

    public static McpServerConfig create(String url, String description, String apiKey) {
        return new McpServerConfig(url, description, apiKey);
    }
}
