package com.example.deepseek.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record McpInitializeParams(
    @JsonProperty("protocolVersion") String protocolVersion,
    McpCapabilities capabilities,
    @JsonProperty("clientInfo") ClientInfo clientInfo
) {
    public record ClientInfo(
        String name,
        String version
    ) {}

    public static McpInitializeParams create(String clientName, String clientVersion) {
        return new McpInitializeParams(
            "2024-11-05",
            McpCapabilities.clientDefault(),
            new ClientInfo(clientName, clientVersion)
        );
    }
}
