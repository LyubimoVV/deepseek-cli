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
import java.util.Map;

/**
 * Клиент для взаимодействия с DeepSeek API.
 * Наследуется от AbstractAiClient для общей функциональности.
 */
public class DeepSeekClient extends AbstractAiClient {

    // Константы API
    private static final String API_URL = "https://api.deepseek.com/v1/chat/completions";
    public static final String MODEL_CHAT = "deepseek-chat";
    public static final String MODEL_REASONER = "deepseek-reasoner";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    // Поля специфичные для DeepSeek
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // Настройки специфичные для DeepSeek
    private List<String> stopSequences = new ArrayList<>();
    private boolean stopSequencesEnabled = false;
    private boolean thinkingEnabled = false;
    private String currentModel;

    /**
     * Создает клиент с API ключом и моделью по умолчанию (deepseek-reasoner).
     */
    public DeepSeekClient(String apiKey) {
        this(apiKey, MODEL_REASONER);
    }

    /**
     * Создает клиент с API ключом и указанной моделью.
     */
    public DeepSeekClient(String apiKey, String model) {
        this(apiKey, model, DEFAULT_SYSTEM_MESSAGE);
    }

    /**
     * Создает клиент с API ключом, моделью и системным сообщением.
     */
    public DeepSeekClient(String apiKey, String model, String systemMessage) {
        super(systemMessage);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be null or blank");
        }
        this.apiKey = apiKey;
        this.currentModel = validateModel(model);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Проверяет и валидирует название модели.
     */
    private String validateModel(String model) {
        if (model == null || model.isBlank()) {
            return MODEL_REASONER;
        }
        if (!model.equals(MODEL_CHAT) && !model.equals(MODEL_REASONER)) {
            throw new IllegalArgumentException("Model must be '" + MODEL_CHAT + "' or '" + MODEL_REASONER + "'");
        }
        return model;
    }

    @Override
    protected String sendApiRequest(String userMessage) throws AiException {
        // Создаем копию истории для данного запроса
        List<Message> messages = new ArrayList<>(conversationHistory);
        messages.add(Message.user(userMessage));

        // Формируем запрос с текущими настройками
        Integer tokens = maxTokensEnabled ? maxTokens : null;
        List<String> stop = stopSequencesEnabled && !stopSequences.isEmpty() ? new ArrayList<>(stopSequences) : null;
        Double temp = temperatureEnabled ? temperature : null;

        // Thinking: для reasoner по умолчанию отключён (передаём disabled), для chat по умолчанию не передаём
        Map<String, String> thinkingParam = null;
        if (currentModel.equals(MODEL_REASONER)) {
            // Для reasoner: thinkingEnabled=false -> disabled, thinkingEnabled=true -> не передаём (по умолчанию включён)
            if (!thinkingEnabled) {
                thinkingParam = Map.of("type", "disabled");
            }
        } else {
            // Для chat: thinkingEnabled=true -> enabled, thinkingEnabled=false -> не передаём
            if (thinkingEnabled) {
                thinkingParam = Map.of("type", "enabled");
            }
        }

        ChatRequest request = new ChatRequest(currentModel, messages, tokens, stop, temp, thinkingParam);

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new AiException("Failed to serialize request", e);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

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
                    "API returned error status: " + response.statusCode() + "\n" + response.body(),
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
        double cost = PricingService.calculateCost(currentModel, usage.promptTokens(), usage.completionTokens());
        updateLastMetrics(new RequestMetrics(
                usage.promptTokens(),
                usage.completionTokens(),
                usage.totalTokens(),
                0, // Latency будет добавлен в методе chat()
                cost,
                currentModel
        ));

        return content;
    }

    /**
     * Отправляет ограниченный запрос к API (с принудительными ограничениями).
     */
    public String chatLimited(String userMessage) throws AiException {
        // Сохраняем текущие настройки
        boolean savedMaxTokensEnabled = maxTokensEnabled;
        boolean savedStopSequencesEnabled = stopSequencesEnabled;
        int savedMaxTokens = maxTokens;
        List<String> savedStopSequences = new ArrayList<>(stopSequences);

        // Включаем ограничения для limited запроса
        maxTokensEnabled = true;
        stopSequencesEnabled = true;

        try {
            return chat(userMessage);
        } finally {
            // Восстанавливаем настройки
            maxTokensEnabled = savedMaxTokensEnabled;
            stopSequencesEnabled = savedStopSequencesEnabled;
            maxTokens = savedMaxTokens;
            stopSequences = savedStopSequences;
        }
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
        return "DeepSeek";
    }

    // === Stop Sequences ===

    public List<String> getStopSequences() {
        return new ArrayList<>(stopSequences);
    }

    public void setStopSequences(List<String> stopSequences) {
        this.stopSequences = stopSequences != null ? new ArrayList<>(stopSequences) : new ArrayList<>();
    }

    public boolean isStopSequencesEnabled() {
        return stopSequencesEnabled;
    }

    public void setStopSequencesEnabled(boolean enabled) {
        this.stopSequencesEnabled = enabled;
    }

    // === Model ===

    public void setCurrentModel(String model) {
        this.currentModel = validateModel(model);
    }

    // === Thinking ===

    public boolean isThinkingEnabled() {
        return thinkingEnabled;
    }

    public void setThinkingEnabled(boolean enabled) {
        this.thinkingEnabled = enabled;
    }

    /**
     * Возвращает API ключ (для отладки, может быть null для безопасности).
     */
    public String getApiKey() {
        return apiKey;
    }
}
