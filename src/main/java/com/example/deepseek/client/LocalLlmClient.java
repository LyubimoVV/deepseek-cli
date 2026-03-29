package com.example.deepseek.client;

import com.example.deepseek.config.AppConfig;
import com.example.deepseek.context.ContextStrategyFactory;
import com.example.deepseek.db.SessionRepository;
import com.example.deepseek.dto.ChatRequest;
import com.example.deepseek.dto.ChatResponse;
import com.example.deepseek.dto.LlmResponse;
import com.example.deepseek.dto.Message;
import com.example.deepseek.dto.RequestMetrics;
import com.example.deepseek.dto.TokenUsage;
import com.example.deepseek.dto.Usage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class LocalLlmClient extends AbstractAiClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(180);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String modelName;

    public LocalLlmClient(String modelName) {
        super();
        this.baseUrl = AppConfig.getLocalLlmUrl();
        this.apiKey = AppConfig.getLocalLlmApiKey();
        this.modelName = modelName;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.objectMapper = createObjectMapper();
    }

    public LocalLlmClient(String modelName, String baseUrl, String apiKey) {
        super();
        this.baseUrl = baseUrl != null ? baseUrl : AppConfig.getLocalLlmUrl();
        this.apiKey = apiKey != null ? apiKey : AppConfig.getLocalLlmApiKey();
        this.modelName = modelName;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.objectMapper = createObjectMapper();
    }

    public LocalLlmClient(String modelName, String systemMessage,
                          ContextStrategyFactory strategyFactory, SessionRepository sessionRepository) {
        super(systemMessage, strategyFactory, sessionRepository);
        this.baseUrl = AppConfig.getLocalLlmUrl();
        this.apiKey = AppConfig.getLocalLlmApiKey();
        this.modelName = modelName;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.objectMapper = createObjectMapper();
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    @Override
    protected String sendApiRequest(String userMessage) throws AiException {
        List<Message> messages = getMessagesForRequest(false, userMessage);
        LlmResponse response = sendApiRequestWithMessages(messages);
        return response.content();
    }

    @Override
    protected LlmResponse sendApiRequestWithMessages(List<Message> messages) throws AiException {
        Integer tokens = maxTokensEnabled ? maxTokens : null;
        List<String> stop = stopSequencesEnabled && !stopSequences.isEmpty() ? new ArrayList<>(stopSequences) : null;
        Double temp = temperatureEnabled ? temperature : null;

        ChatRequest request = new ChatRequest(modelName, messages, tokens, stop, temp, null);

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new AiException("Failed to serialize request", e);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json");

        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        requestBuilder.POST(HttpRequest.BodyPublishers.ofString(requestBody));

        HttpRequest httpRequest = requestBuilder.build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("Request interrupted", e);
        } catch (Exception e) {
            throw AiException.networkError("Failed to send request to Local LLM: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            throw new ApiException(
                    response.statusCode(),
                    "Local LLM API error: " + response.statusCode() + " - " + response.body(),
                    response.body(),
                    baseUrl + "/v1/chat/completions",
                    requestBody
            );
        }

        ChatResponse chatResponse;
        try {
            chatResponse = objectMapper.readValue(response.body(), ChatResponse.class);
        } catch (JsonProcessingException e) {
            throw AiException.invalidResponse("Failed to deserialize response: " + e.getMessage());
        }

        String content = chatResponse.getContent();

        Usage usage = chatResponse.getUsage();
        int promptTokens = usage != null ? usage.promptTokens() : 0;
        int completionTokens = usage != null ? usage.completionTokens() : 0;

        updateLastMetrics(new RequestMetrics(
                promptTokens,
                completionTokens,
                usage != null ? usage.totalTokens() : 0,
                0,
                0,
                0.0,
                modelName
        ));

        TokenUsage tokenUsage = new TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens);
        return new LlmResponse(content, tokenUsage);
    }

    @Override
    public String getCurrentModel() {
        return modelName;
    }

    @Override
    public String getModelDisplayName() {
        return "Free Ollama: " + modelName;
    }

    @Override
    public String getProviderName() {
        return "Free Ollama";
    }

    public static boolean isAvailable() {
        return isAvailable(AppConfig.getLocalLlmUrl());
    }

    public static boolean isAvailable(String baseUrl) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public static List<String> getAvailableModels() {
        return getAvailableModels(AppConfig.getLocalLlmUrl(), AppConfig.getLocalLlmApiKey());
    }

    public static List<String> getAvailableModels(String baseUrl, String apiKey) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/models"))
                    .GET()
                    .timeout(Duration.ofSeconds(5));
            
            if (apiKey != null && !apiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }
            
            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return List.of();
            }

            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            LocalLlmModelsResponse modelsResponse = mapper.readValue(response.body(), LocalLlmModelsResponse.class);

            List<String> models = new ArrayList<>();
            if (modelsResponse.data() != null) {
                for (LocalLlmModel model : modelsResponse.data()) {
                    models.add(model.id());
                }
            }
            return models;
        } catch (Exception e) {
            return List.of();
        }
    }
}

record LocalLlmModelsResponse(List<LocalLlmModel> data) {}

record LocalLlmModel(String id, String object, long created, String owned_by) {}
