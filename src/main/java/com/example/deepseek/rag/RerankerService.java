package com.example.deepseek.rag;

import com.example.deepseek.embedding.index.SearchResult;
import com.example.deepseek.embedding.ollama.RerankerClient;
import com.example.deepseek.embedding.ollama.RerankerClient.DocumentWithId;
import com.example.deepseek.embedding.ollama.RerankerClient.RerankResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class RerankerService {
    private static final Logger log = LoggerFactory.getLogger(RerankerService.class);

    private final RerankerClient rerankerClient;

    public RerankerService(RerankerClient rerankerClient) {
        this.rerankerClient = rerankerClient;
    }

    public List<SearchResult> rerank(String query, List<SearchResult> results, double threshold, int topK) {
        if (results == null || results.isEmpty()) {
            log.debug("No results to rerank");
            return List.of();
        }

        if (rerankerClient == null) {
            log.warn("RerankerClient is null, returning original results filtered");
            return filterByThreshold(results, threshold, topK);
        }
        
        if (!rerankerClient.isAvailable()) {
            log.warn("Ollama not available, returning original results filtered");
            return filterByThreshold(results, threshold, topK);
        }

        if (!rerankerClient.hasRerankerModel()) {
            log.warn("Reranker model {} not available, returning original results filtered", 
                rerankerClient.getModelName());
            return filterByThreshold(results, threshold, topK);
        }

        try {
            log.info("Reranking {} results with threshold={}, topK={}", results.size(), threshold, topK);

            List<DocumentWithId> docs = results.stream()
                .map(r -> new DocumentWithId(r.chunkId(), r.content()))
                .toList();

            List<RerankResult> reranked = rerankerClient.rerankWithScores(query, docs);

            log.info("Reranker returned {} results", reranked.size());

            List<SearchResult> rerankedResults = new ArrayList<>();
            for (RerankResult rr : reranked) {
                if (rr.score() >= threshold) {
                    SearchResult original = findByChunkId(results, rr.documentId());
                    if (original != null) {
                        rerankedResults.add(new SearchResult(
                            original.chunkId(),
                            rr.score(),
                            original.content(),
                            original.source(),
                            original.title(),
                            original.section(),
                            rr.score()
                        ));
                    }
                }
            }

            rerankedResults.sort((a, b) -> Double.compare(b.rerankScore(), a.rerankScore()));

            if (rerankedResults.size() > topK) {
                rerankedResults = rerankedResults.subList(0, topK);
            }

            log.info("Reranking complete: {} results after threshold filter and topK", rerankedResults.size());
            
            for (int i = 0; i < rerankedResults.size(); i++) {
                SearchResult r = rerankedResults.get(i);
                log.debug("  [{}] rerankScore={} chunkId={}", 
                    i + 1, String.format("%.4f", r.rerankScore()), r.chunkId());
            }

            return rerankedResults;
        } catch (Exception e) {
            log.error("Reranking failed: {}", e.getMessage(), e);
            return filterByThreshold(results, threshold, topK);
        }
    }

    private SearchResult findByChunkId(List<SearchResult> results, String chunkId) {
        return results.stream()
            .filter(r -> r.chunkId().equals(chunkId))
            .findFirst()
            .orElse(null);
    }

    private List<SearchResult> filterByThreshold(List<SearchResult> results, double threshold, int topK) {
        return results.stream()
            .filter(r -> r.score() >= threshold)
            .sorted((a, b) -> Double.compare(b.score(), a.score()))
            .limit(topK)
            .collect(Collectors.toList());
    }

    public boolean isAvailable() {
        return rerankerClient != null && 
               rerankerClient.isAvailable() && 
               rerankerClient.hasRerankerModel();
    }

    public String getModelName() {
        return rerankerClient != null ? rerankerClient.getModelName() : null;
    }

    public RerankerClient getRerankerClient() {
        return rerankerClient;
    }
}
