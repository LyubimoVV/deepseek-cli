package com.example.deepseek.client;

import com.example.deepseek.dto.ChatRequest;
import com.example.deepseek.dto.ChatResponse;
import com.example.deepseek.dto.Message;
import com.example.deepseek.dto.RequestMetrics;
import com.example.deepseek.dto.Usage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Клиент для взаимодействия с OpenRouter API.
 * OpenRouter предоставляет единый интерфейс для множества AI моделей.
 * Наследуется от AbstractAiClient для общей функциональности.
 */
public class OpenRouterClient extends AbstractAiClient {

    // Константы API
    private static final String API_URL = "https://openrouter.ai/api/v1/chat/completions";
    
    // Популярные бесплатные модели
    public static final String MODEL_GPT_OSS = "openai/gpt-oss-20b:free";
    public static final String MODEL_LFM_2_5 = "liquid/lfm-2.5-1.2b-instruct:free";
    
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    // Поля специфичные для OpenRouter
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private String currentModel;
    private String siteUrl;  // Optional: для рейтинга на openrouter.ai
    private String siteName; // Optional: название сайта

    /**
     * Создает клиент с API ключом и моделью по умолчанию (GPT-OSS 20B).
     */
    public OpenRouterClient(String apiKey) {
        this(apiKey, MODEL_GPT_OSS);
    }

    /**
     * Создает клиент с API ключом и указанной моделью.
     */
    public OpenRouterClient(String apiKey, String model) {
        this(apiKey, model, DEFAULT_SYSTEM_MESSAGE);
    }

    /**
     * Создает клиент с API ключом, моделью и системным сообщением.
     */
    public OpenRouterClient(String apiKey, String model, String systemMessage) {
        super(systemMessage);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be null or blank");
        }
        this.apiKey = apiKey;
        this.currentModel = model != null && !model.isBlank() ? model : MODEL_GPT_OSS;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    protected String sendApiRequest(String userMessage) throws AiException {
        // Создаем копию истории для данного запроса (сообщение уже добавлено в chat())
        List<Message> messages = new ArrayList<>(conversationHistory);

        // Формируем запрос с текущими настройками
        Integer tokens = maxTokensEnabled ? maxTokens : null;
        Double temp = temperatureEnabled ? temperature : null;

        ChatRequest request = new ChatRequest(currentModel, messages, tokens, null, temp, null);

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new AiException("Failed to serialize request", e);
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("HTTP-Referer", siteUrl != null ? siteUrl : "http://localhost:8080")
                .header("X-Title", siteName != null ? siteName : "AI Chat Interface")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));

        HttpRequest httpRequest = requestBuilder.build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("Request interrupted", e);
        } catch (Exception e) {
            throw AiException.networkError("Failed to send request: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            throw new ApiException(
                    response.statusCode(),
                    "OpenRouter API returned error status: " + response.statusCode() + "\n" + response.body(),
                    response.body(),
                    API_URL,
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

        // Собираем метрики
        Usage usage = chatResponse.getUsage();
        int cachedTokens = usage != null ? usage.getCachedTokens() : 0;
        double cost = PricingService.calculateCost(currentModel, 
                usage != null ? usage.promptTokens() : 0, 
                usage != null ? usage.completionTokens() : 0);
        
        updateLastMetrics(new RequestMetrics(
                usage != null ? usage.promptTokens() : 0,
                usage != null ? usage.completionTokens() : 0,
                usage != null ? usage.totalTokens() : 0,
                cachedTokens,
                0, // Latency будет добавлен в методе chat()
                cost,
                currentModel
        ));

        return content;
    }

    @Override
    public String getCurrentModel() {
        return currentModel;
    }

    @Override
    public String getModelDisplayName() {
        return PricingService.getModelDisplayName(currentModel);
    }

    @Override
    public String getProviderName() {
        return "OpenRouter";
    }

    // === Model ===

    public void setCurrentModel(String model) {
        if (model == null || model.isBlank()) {
            this.currentModel = MODEL_GPT_OSS;
        } else {
            this.currentModel = model;
        }
    }

    // === Site Info (optional, for ranking on openrouter.ai) ===

    public void setSiteUrl(String siteUrl) {
        this.siteUrl = siteUrl;
    }

    public void setSiteName(String siteName) {
        this.siteName = siteName;
    }

    /**
     * Возвращает API ключ (для отладки, может быть null для безопасности).
     */
    public String getApiKey() {
        return apiKey;
    }
}
