package com.example.deepseek.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Сообщение в ответе от API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResponseMessage(
        @JsonProperty("content") String content
) {
}
