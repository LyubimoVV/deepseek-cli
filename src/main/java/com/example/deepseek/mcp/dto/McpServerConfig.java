package com.example.deepseek.mcp.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record McpServerConfig(
    String url,
    String description,
    Map<String, String> headers
) {
    @JsonCreator
    public McpServerConfig {}

    public static McpServerConfig create(String url, String description) {
        return new McpServerConfig(url, description, Map.of());
    }
}
