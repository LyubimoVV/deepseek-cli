package com.example.deepseek.embedding;

public record ChunkMetadata(
    String chunkId,
    String source,
    String title,
    String section,
    int position,
    int startLine,
    int endLine,
    String strategy
) {
    public static ChunkMetadata create(
        String source, String title, String section,
        int position, int startLine, int endLine, String strategy
    ) {
        return new ChunkMetadata(
            java.util.UUID.randomUUID().toString(),
            source, title, section, position, startLine, endLine, strategy
        );
    }
}
