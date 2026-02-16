package com.example.deepseek.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Выбор ответа в ответе API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Choice(ResponseMessage message) {
}
