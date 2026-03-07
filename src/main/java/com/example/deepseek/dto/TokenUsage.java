package com.example.deepseek.dto;

public record TokenUsage(
    int inputTokens,
    int outputTokens,
    int totalTokens
) {}
