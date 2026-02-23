package com.example.deepseek.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Ответ от DeepSeek Chat API.
 * 
 * @param choices список вариантов ответа
 * @param usage   метрики использования токенов
 */
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
    
    /**
     * Возвращает метрики использования токенов.
     * Если usage отсутствует, возвращает пустой объект.
     */
    public Usage getUsage() {
        return usage != null ? usage : Usage.empty();
    }
}
