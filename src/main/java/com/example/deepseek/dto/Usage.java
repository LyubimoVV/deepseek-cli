package com.example.deepseek.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Метрики использования токенов в ответе от DeepSeek API.
 * 
 * @param promptTokens     количество токенов во входном запросе (input)
 * @param completionTokens количество токенов в ответе (output)
 * @param totalTokens      общее количество токенов
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Usage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("total_tokens") int totalTokens
) {
    
    /**
     * Создаёт пустой Usage с нулевыми значениями.
     */
    public static Usage empty() {
        return new Usage(0, 0, 0);
    }
}
