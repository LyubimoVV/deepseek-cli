package com.example.deepseek.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatResponse(
        List<Choice> choices,
        Usage usage
) {
    
    public String getContent() {
        if (choices == null || choices.isEmpty()) {
            return "";
        }
        Choice choice = choices.get(0);
        if (choice == null || choice.message() == null) {
            return "";
        }
        String content = choice.message().content();
        if (content == null) {
            return "";
        }
        content = content.replace("\\n", "\n");
        content = content.replaceAll("\n{3,}", "\n\n");
        return content;
    }
    
    public boolean hasToolCalls() {
        if (choices == null || choices.isEmpty()) {
            return false;
        }
        Choice choice = choices.get(0);
        if (choice == null || choice.message() == null) {
            return false;
        }
        List<ToolCallDto> toolCalls = choice.message().toolCalls();
        return toolCalls != null && !toolCalls.isEmpty();
    }
    
    public List<ToolCallDto> getToolCalls() {
        if (choices == null || choices.isEmpty()) {
            return List.of();
        }
        Choice choice = choices.get(0);
        if (choice == null || choice.message() == null) {
            return List.of();
        }
        List<ToolCallDto> toolCalls = choice.message().toolCalls();
        return toolCalls != null ? toolCalls : List.of();
    }
    
    public String getFinishReason() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        Choice choice = choices.get(0);
        return choice != null ? choice.finishReason() : null;
    }
    
    public Usage getUsage() {
        return usage != null ? usage : Usage.empty();
    }
}
