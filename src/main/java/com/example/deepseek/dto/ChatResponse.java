package com.example.deepseek.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Ответ от DeepSeek Chat API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatResponse(
        List<Choice> choices
) {
    
    public String getContent() {
        if (choices == null || choices.isEmpty()) {
            return "";
        }
        Choice choice = choices.get(0);
        if (choice == null || choice.message() == null) {
            return "";
        }
        return choice.message().content();
    }
}
