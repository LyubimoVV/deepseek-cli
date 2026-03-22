package com.example.deepseek.mcp.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record McpInitializeResult(
    @JsonProperty("protocolVersion") String protocolVersion,
    McpCapabilities capabilities,
    @JsonProperty("serverInfo") ServerInfo serverInfo,
    @JsonProperty("instructions") String instructions
) {
    @JsonCreator
    public McpInitializeResult {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServerInfo(
        String name,
        String version
    ) {
        @JsonCreator
        public ServerInfo {}
    }
}
