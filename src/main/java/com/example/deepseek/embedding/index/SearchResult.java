package com.example.deepseek.embedding.index;

import java.util.List;

public record SearchResult(
    String chunkId,
    double score,
    String content,
    String source,
    String title,
    String section,
    Double rerankScore
) {
    public SearchResult(String chunkId, double score, String content, String source, String title, String section) {
        this(chunkId, score, content, source, title, section, null);
    }
}
