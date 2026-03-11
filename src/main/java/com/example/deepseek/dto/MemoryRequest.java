package com.example.deepseek.dto;

public record MemoryRequest(
    String category,
    String key,
    String value
) {
}
