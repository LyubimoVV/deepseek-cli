package com.example.deepseek.embedding.index;

import java.util.List;

public interface VectorIndex {
    void add(String chunkId, float[] embedding);
    void addAll(List<String> chunkIds, List<float[]> embeddings);
    List<SearchResult> search(float[] query, int k);
    boolean remove(String chunkId);
    void clear();
    int size();
    int getDimension();
    boolean isEmpty();
}
