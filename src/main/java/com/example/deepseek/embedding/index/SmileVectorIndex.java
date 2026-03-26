package com.example.deepseek.embedding.index;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class SmileVectorIndex implements VectorIndex {
    private final Map<String, float[]> idToEmbedding;
    private final Map<String, Integer> idToIndex;
    private final List<String> indexToId;
    private final List<float[]> embeddings;
    private final int dimension;

    public SmileVectorIndex(int dimension) {
        this.dimension = dimension;
        this.idToEmbedding = new ConcurrentHashMap<>();
        this.idToIndex = new ConcurrentHashMap<>();
        this.indexToId = Collections.synchronizedList(new ArrayList<>());
        this.embeddings = Collections.synchronizedList(new ArrayList<>());
    }

    @Override
    public synchronized void add(String chunkId, float[] embedding) {
        validateDimension(embedding);
        
        if (idToEmbedding.containsKey(chunkId)) {
            int idx = idToIndex.get(chunkId);
            embeddings.set(idx, embedding.clone());
            idToEmbedding.put(chunkId, embedding.clone());
        } else {
            int idx = indexToId.size();
            indexToId.add(chunkId);
            embeddings.add(embedding.clone());
            idToIndex.put(chunkId, idx);
            idToEmbedding.put(chunkId, embedding.clone());
        }
    }

    @Override
    public synchronized void addAll(List<String> chunkIds, List<float[]> embList) {
        if (chunkIds.size() != embList.size()) {
            throw new IllegalArgumentException("chunkIds and embeddings must have same size");
        }

        for (int i = 0; i < chunkIds.size(); i++) {
            add(chunkIds.get(i), embList.get(i));
        }
    }

    @Override
    public List<SearchResult> search(float[] query, int k) {
        if (isEmpty()) {
            return List.of();
        }

        validateDimension(query);

        return idToEmbedding.entrySet().parallelStream()
            .map(entry -> {
                double similarity = cosineSimilarity(query, entry.getValue());
                return new AbstractMap.SimpleEntry<>(entry.getKey(), similarity);
            })
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .limit(k)
            .map(entry -> new SearchResult(
                entry.getKey(),
                entry.getValue(),
                null, null, null, null
            ))
            .collect(Collectors.toList());
    }

    @Override
    public synchronized boolean remove(String chunkId) {
        if (!idToEmbedding.containsKey(chunkId)) {
            return false;
        }
        
        idToEmbedding.remove(chunkId);
        Integer idx = idToIndex.remove(chunkId);
        if (idx != null) {
            embeddings.set(idx, null);
        }
        indexToId.remove(chunkId);
        return true;
    }

    @Override
    public synchronized void clear() {
        idToEmbedding.clear();
        idToIndex.clear();
        indexToId.clear();
        embeddings.clear();
    }

    @Override
    public int size() {
        return idToEmbedding.size();
    }

    @Override
    public int getDimension() {
        return dimension;
    }

    @Override
    public boolean isEmpty() {
        return idToEmbedding.isEmpty();
    }

    public float[] getEmbedding(String chunkId) {
        return idToEmbedding.get(chunkId);
    }

    private void validateDimension(float[] embedding) {
        if (embedding.length != dimension) {
            throw new IllegalArgumentException(
                "Embedding dimension mismatch: expected " + dimension + ", got " + embedding.length
            );
        }
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0 || normB == 0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
