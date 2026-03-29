package com.example.deepseek.embedding.ollama;

import com.example.deepseek.config.AppConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RerankerClient {
    private static final Logger log = LoggerFactory.getLogger(RerankerClient.class);
    private static final int TIMEOUT_SECONDS = 120;

    private final String serviceUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private Boolean availableCache;
    private long cacheTimestamp;
    private static final long CACHE_TTL_MS = 30_000;

    public RerankerClient() {
        this(AppConfig.getRerankerServiceUrl());
    }

    public RerankerClient(String serviceUrl) {
        this.serviceUrl = serviceUrl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = new ObjectMapper();
    }

    public List<RerankResult> rerank(String query, List<String> documents) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query cannot be null or blank");
        }
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        List<DocumentWithId> docs = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            docs.add(new DocumentWithId(String.valueOf(i), documents.get(i)));
        }
        
        List<RerankResult> results = rerankWithScores(query, docs);
        
        List<RerankResult> mappedResults = new ArrayList<>();
        for (RerankResult r : results) {
            try {
                int idx = Integer.parseInt(r.documentId());
                mappedResults.add(new RerankResult(idx, r.score(), r.documentId()));
            } catch (NumberFormatException e) {
                log.warn("Invalid document id: {}", r.documentId());
            }
        }
        
        return mappedResults;
    }

    public List<RerankResult> rerankWithScores(String query, List<DocumentWithId> documentsWithIds) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query cannot be null or blank");
        }
        if (documentsWithIds == null || documentsWithIds.isEmpty()) {
            return List.of();
        }

        try {
            List<Map<String, String>> docs = documentsWithIds.stream()
                .map(d -> Map.of("id", d.id(), "text", d.content()))
                .toList();
            
            log.info("Reranking query: '{}' with {} documents", query, docs.size());
            for (int i = 0; i < Math.min(3, docs.size()); i++) {
                String preview = docs.get(i).get("text");
                preview = preview.length() > 100 ? preview.substring(0, 100) + "..." : preview;
                log.info("  Doc[{}]: {}", i, preview);
            }
            
            Map<String, Object> request = Map.of(
                "query", query,
                "documents", docs
            );
            String json = objectMapper.writeValueAsString(request);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(serviceUrl + "/rerank"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Reranker API error: " + response.statusCode() + " - " + response.body());
            }

            RerankResponse rerankResponse = objectMapper.readValue(response.body(), RerankResponse.class);
            
            List<RerankResult> results = new ArrayList<>();
            for (RerankResultItem item : rerankResponse.results()) {
                results.add(new RerankResult(0, item.score(), item.id()));
            }
            
            return results;
        } catch (Exception e) {
            throw new RuntimeException("Failed to rerank documents: " + e.getMessage(), e);
        }
    }

    public boolean isAvailable() {
        long now = System.currentTimeMillis();
        if (availableCache != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return availableCache;
        }
        
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serviceUrl + "/health"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                HealthResponse health = objectMapper.readValue(response.body(), HealthResponse.class);
                availableCache = "ok".equals(health.status());
            } else {
                availableCache = false;
            }
            
            cacheTimestamp = now;
            log.debug("Reranker service availability check: {} (status={})", availableCache, response.statusCode());
            return availableCache;
        } catch (Exception e) {
            log.warn("Reranker service not available: {}", e.getMessage());
            availableCache = false;
            cacheTimestamp = now;
            return false;
        }
    }

    public boolean hasRerankerModel() {
        return isAvailable();
    }

    public String getModelName() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serviceUrl + "/model"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                ModelInfo info = objectMapper.readValue(response.body(), ModelInfo.class);
                return info.name();
            }
        } catch (Exception e) {
            log.debug("Failed to get model info: {}", e.getMessage());
        }
        return "bge-reranker-v2-m3";
    }

    public record RerankResult(
        int index,
        double score,
        String documentId
    ) {}

    public record DocumentWithId(
        String id,
        String content
    ) {}
}

record RerankResponse(
    @JsonProperty("results") List<RerankResultItem> results
) {}

record RerankResultItem(
    @JsonProperty("id") String id,
    @JsonProperty("score") double score
) {}

record HealthResponse(
    @JsonProperty("status") String status,
    @JsonProperty("model") String model
) {}

record ModelInfo(
    @JsonProperty("name") String name,
    @JsonProperty("batch_size") int batchSize,
    @JsonProperty("fp16") boolean fp16
) {}
