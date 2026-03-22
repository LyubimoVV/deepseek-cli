package com.example.deepseek.mcp.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record McpToolsListResult(
    List<McpTool> tools,
    @JsonProperty("nextCursor") String nextCursor
) {
    @JsonCreator
    public McpToolsListResult {}
}
