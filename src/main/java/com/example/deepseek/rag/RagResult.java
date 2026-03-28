package com.example.deepseek.rag;

import java.util.List;

public record RagResult(
    String augmentedPrompt,
    List<SourceInfo> sources,
    boolean hasRelevantResults,
    double maxRelevanceScore
) {
    public record SourceInfo(
        String chunkId,
        String source,
        String section,
        String content,
        double score
    ) {}
}
