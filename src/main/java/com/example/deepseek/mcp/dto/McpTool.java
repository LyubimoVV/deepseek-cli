package com.example.deepseek.mcp.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record McpTool(
    String name,
    String description,
    @JsonProperty("inputSchema") InputSchema inputSchema
) {
    @JsonCreator
    public McpTool {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InputSchema(
        String type,
        Map<String, Object> properties,
        @JsonProperty("required") java.util.List<String> required
    ) {
        @JsonCreator
        public InputSchema {}
    }
}
