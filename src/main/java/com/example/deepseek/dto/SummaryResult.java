package com.example.deepseek.dto;

public record SummaryResult(
    String content,
    int inputTokens,
    int outputTokens,
    int totalTokens,
    double cost
) {
    public String summary() {
        return content;
    }
}
