package com.example.deepseek.embedding.ollama;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RerankerClient {
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";
    private static final String RERANKER_MODEL = "qllama/bge-reranker-v2-m3";
    private static final int TIMEOUT_SECONDS = 120;

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public RerankerClient() {
        this(DEFAULT_BASE_URL);
    }

    public RerankerClient(String baseUrl) {
        this.baseUrl = baseUrl;
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

        try {
            double[] queryEmbedding = getEmbedding(query);
            
            List<double[]> docEmbeddings = new ArrayList<>();
            for (String doc : documents) {
                double[] emb = getEmbedding(doc);
                docEmbeddings.add(emb);
            }
            
            List<RerankResult> results = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                double similarity = cosineSimilarity(queryEmbedding, docEmbeddings.get(i));
                double normalizedScore = (similarity + 1.0) / 2.0;
                results.add(new RerankResult(i, normalizedScore, null));
            }
            
            results.sort((a, b) -> Double.compare(b.score(), a.score()));
            
            return results;
        } catch (Exception e) {
            throw new RuntimeException("Failed to rerank documents: " + e.getMessage(), e);
        }
    }

    public List<RerankResult> rerankWithScores(String query, List<DocumentWithId> documentsWithIds) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Query cannot be null or blank");
        }
        if (documentsWithIds == null || documentsWithIds.isEmpty()) {
            return List.of();
        }

        List<String> documents = documentsWithIds.stream()
            .map(DocumentWithId::content)
            .toList();

        List<RerankResult> results = rerank(query, documents);
        
        List<RerankResult> mappedResults = new ArrayList<>();
        for (RerankResult r : results) {
            int idx = r.index();
            if (idx >= 0 && idx < documentsWithIds.size()) {
                DocumentWithId doc = documentsWithIds.get(idx);
                mappedResults.add(new RerankResult(idx, r.score(), doc.id()));
            }
        }
        
        return mappedResults;
    }

    private double[] getEmbedding(String text) {
        try {
            Map<String, String> request = Map.of(
                "model", RERANKER_MODEL,
                "prompt", text
            );
            String json = objectMapper.writeValueAsString(request);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/embeddings"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Embedding API error: " + response.statusCode() + " - " + response.body());
            }

            RerankerEmbeddingResponse embResponse = objectMapper.readValue(response.body(), RerankerEmbeddingResponse.class);
            return toPrimitiveArray(embResponse.embedding());
        } catch (Exception e) {
            throw new RuntimeException("Failed to get embedding: " + e.getMessage(), e);
        }
    }

    private double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors must have same length");
        }
        
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
    
    private double[] toPrimitiveArray(List<Double> list) {
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean hasRerankerModel() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                return response.body().contains("bge-reranker-v2-m3");
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public String getModelName() {
        return RERANKER_MODEL;
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

record RerankerEmbeddingResponse(
    @JsonProperty("embedding") List<Double> embedding
) {}
