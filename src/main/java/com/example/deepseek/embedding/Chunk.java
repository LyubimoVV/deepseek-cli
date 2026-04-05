package com.example.deepseek.embedding;

public record Chunk(
    ChunkMetadata metadata,
    String content
) {}
