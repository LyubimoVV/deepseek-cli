package com.example.deepseek.embedding.ollama;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class OllamaClient {
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";
    private static final String EMBEDDING_MODEL = "nomic-embed-text";
    private static final int TIMEOUT_SECONDS = 60;

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OllamaClient() {
        this(DEFAULT_BASE_URL);
    }

    public OllamaClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = new ObjectMapper();
    }

    public float[] embed(String text) {
        try {
            EmbeddingRequest request = new EmbeddingRequest(EMBEDDING_MODEL, text);
            String json = objectMapper.writeValueAsString(request);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/embeddings"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Ollama API error: " + response.statusCode() + " - " + response.body());
            }

            EmbeddingResponse embeddingResponse = objectMapper.readValue(response.body(), EmbeddingResponse.class);
            return toFloatArray(embeddingResponse.embedding());
        } catch (Exception e) {
            throw new RuntimeException("Failed to get embedding from Ollama: " + e.getMessage(), e);
        }
    }

    public List<float[]> embedBatch(List<String> texts) {
        return texts.stream().map(this::embed).toList();
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

    public boolean hasModel() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                return response.body().contains(EMBEDDING_MODEL);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private float[] toFloatArray(List<Double> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i).floatValue();
        }
        return arr;
    }

    public String getModelName() {
        return EMBEDDING_MODEL;
    }
}

record EmbeddingRequest(
    String model,
    String prompt
) {}

record EmbeddingResponse(
    @JsonProperty("embedding") List<Double> embedding
) {}
