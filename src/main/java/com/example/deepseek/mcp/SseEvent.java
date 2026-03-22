package com.example.deepseek.mcp;

public record SseEvent(
    String type,
    String id,
    String data
) {}
