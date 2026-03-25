package com.example.deepseek.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(
    String role,
    String content,
    @JsonProperty("tool_calls") List<ToolCallDto> toolCalls,
    @JsonProperty("tool_call_id") String toolCallId,
    String name
) {
    public Message(String role, String content) {
        this(role, content, null, null, null);
    }
    
    public static Message system(String content) {
        return new Message("system", content, null, null, null);
    }
    
    public static Message user(String content) {
        return new Message("user", content, null, null, null);
    }
    
    public static Message assistant(String content) {
        return new Message("assistant", content, null, null, null);
    }
    
    public static Message assistantWithTools(String content, List<ToolCallDto> toolCalls) {
        return new Message("assistant", content, toolCalls, null, null);
    }
    
    public static Message toolResult(String toolCallId, String name, String content) {
        return new Message("tool", content, null, toolCallId, name);
    }
}
