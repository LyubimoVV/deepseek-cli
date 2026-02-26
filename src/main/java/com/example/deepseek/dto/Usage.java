package com.example.deepseek.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Метрики использования токенов в ответе от DeepSeek API.
 * 
 * @param promptTokens           количество токенов во входном запросе (input)
 * @param completionTokens       количество токенов в ответе (output)
 * @param totalTokens            общее количество токенов
 * @param promptTokensDetails    детали входных токенов (включая cached)
 * @param promptCacheHitTokens   количество токенов, попавших в кэш
 * @param promptCacheMissTokens  количество токенов, не попавших в кэш
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Usage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("total_tokens") int totalTokens,
        @JsonProperty("prompt_tokens_details") PromptTokensDetails promptTokensDetails,
        @JsonProperty("prompt_cache_hit_tokens") int promptCacheHitTokens,
        @JsonProperty("prompt_cache_miss_tokens") int promptCacheMissTokens
) {
    
    /**
     * Создаёт пустой Usage с нулевыми значениями.
     */
    public static Usage empty() {
        return new Usage(0, 0, 0, null, 0, 0);
    }
    
    /**
     * Возвращает количество закешированных токенов.
     */
    public int getCachedTokens() {
        if (promptTokensDetails != null) {
            return promptTokensDetails.cachedTokens();
        }
        return promptCacheHitTokens;
    }
    
    /**
     * Вложенный record для деталей входных токенов.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PromptTokensDetails(
            @JsonProperty("cached_tokens") int cachedTokens
    ) {}
}
