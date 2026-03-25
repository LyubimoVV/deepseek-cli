package com.example.deepseek.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResponseMessage(
        @JsonProperty("content") String content,
        @JsonProperty("tool_calls") List<ToolCallDto> toolCalls,
        @JsonProperty("reasoning_content") String reasoningContent
) {
    public ResponseMessage {
        content = content != null ? content : "";
    }
}
