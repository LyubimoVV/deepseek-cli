package com.example.deepseek.mcp.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record McpCapabilities(
    @JsonProperty("experimental") Map<String, Object> experimental,
    ToolsCapabilities tools,
    ResourcesCapabilities resources,
    CompletionsCapabilities completions,
    PromptsCapabilities prompts
) {
    @JsonCreator
    public McpCapabilities {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolsCapabilities(
        @JsonProperty("listChanged") Boolean listChanged
    ) {
        @JsonCreator
        public ToolsCapabilities {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResourcesCapabilities(
        @JsonProperty("subscribe") Boolean subscribe,
        @JsonProperty("listChanged") Boolean listChanged
    ) {
        @JsonCreator
        public ResourcesCapabilities {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompletionsCapabilities(
        @JsonProperty("listChanged") Boolean listChanged
    ) {
        @JsonCreator
        public CompletionsCapabilities {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PromptsCapabilities(
        @JsonProperty("listChanged") Boolean listChanged
    ) {
        @JsonCreator
        public PromptsCapabilities {}
    }

    public static McpCapabilities clientDefault() {
        return new McpCapabilities(
            Map.of(),
            new ToolsCapabilities(null),
            new ResourcesCapabilities(null, null),
            new CompletionsCapabilities(null),
            new PromptsCapabilities(null)
        );
    }
}
