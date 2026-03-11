package com.example.deepseek.memory.dto;

import com.example.deepseek.memory.MemoryLayer;

public record MemorySuggestion(
    String key,
    String value,
    MemoryLayer layer,
    String category,
    double confidence,
    String explanation
) {
}
