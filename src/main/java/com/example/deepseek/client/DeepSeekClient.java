package com.example.deepseek.client;

import com.example.deepseek.dto.ChatRequest;
import com.example.deepseek.dto.ChatResponse;
import com.example.deepseek.dto.Message;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Клиент для взаимодействия с DeepSeek API.
 */
public class DeepSeekClient {

    private static final String API_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-chat";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final List<Message> conversationHistory;

    // Системные сообщения
    private static final String SYSTEM_MESSAGE_HELPER = "Ты полезный помощник";
    private static final String SYSTEM_MESSAGE_TESTER = "Ты senior тестировщик из Google с 10+ годами опыта. Объясняй концепции тестирования простыми словами, как будто объясняешь джуниору на первом дне работы. Используй практические примеры из реальной разработки. Отвечай кратко и структурированно.";

    // Настройки с возможностью включения/выключения
    private int maxTokens = 200;
    private boolean maxTokensEnabled = false;
    
    private List<String> stopSequences = new ArrayList<>();
    private boolean stopSequencesEnabled = false;
    
    private double temperature = 1.0;
    private boolean temperatureEnabled = false;
    
    private String currentSystemMessage = SYSTEM_MESSAGE_HELPER;

    public DeepSeekClient(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be null or blank");
        }
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
        this.conversationHistory = new ArrayList<>();
        this.conversationHistory.add(Message.system(currentSystemMessage));
    }

    /**
     * Отправляет запрос к API с текущими настройками.
     */
    public String chat(String userMessage) throws IOException {
        return sendRequest(userMessage);
    }
    
    /**
     * Отправляет ограниченный запрос к API (с принудительными ограничениями).
     */
    public String chatLimited(String userMessage) throws IOException {
        // Сохраняем текущие настройки
        boolean savedMaxTokensEnabled = maxTokensEnabled;
        boolean savedStopSequencesEnabled = stopSequencesEnabled;
        int savedMaxTokens = maxTokens;
        List<String> savedStopSequences = new ArrayList<>(stopSequences);
        
        // Включаем ограничения для limited запроса
        maxTokensEnabled = true;
        stopSequencesEnabled = true;
        
        try {
            return sendRequest(userMessage);
        } finally {
            // Восстанавливаем настройки
            maxTokensEnabled = savedMaxTokensEnabled;
            stopSequencesEnabled = savedStopSequencesEnabled;
            maxTokens = savedMaxTokens;
            stopSequences = savedStopSequences;
        }
    }

    /**
     * Отправляет запрос к API и возвращает ответ.
     */
    private String sendRequest(String userMessage) throws IOException {
        // Создаем копию истории для данного запроса
        List<Message> messages = new ArrayList<>(conversationHistory);
        messages.add(Message.user(userMessage));

        // Формируем запрос с включенными настройками
        Integer tokens = maxTokensEnabled ? maxTokens : null;
        List<String> stop = stopSequencesEnabled && !stopSequences.isEmpty() ? new ArrayList<>(stopSequences) : null;
        Double temp = temperatureEnabled ? temperature : null;
        
        ChatRequest request = new ChatRequest(DEFAULT_MODEL, messages, tokens, stop, temp);

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize request", e);
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
            throw new RuntimeException("Request interrupted", e);
        }

        if (response.statusCode() != 200) {
            throw new ApiException(
                    response.statusCode(),
                    "API returned error status: " + response.statusCode() + "\n" + response.body()
            );
        }

        ChatResponse chatResponse;
        try {
            chatResponse = objectMapper.readValue(response.body(), ChatResponse.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize response", e);
        }

        String content = chatResponse.getContent();

        // Добавляем в историю
        conversationHistory.add(Message.user(userMessage));
        conversationHistory.add(Message.assistant(content));

        return content;
    }

    /**
     * Очищает историю разговора.
     */
    public void clearHistory() {
        conversationHistory.clear();
        this.conversationHistory.add(Message.system(currentSystemMessage));
    }

    // === Max Tokens ===
    
    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        if (maxTokens < 1) {
            throw new IllegalArgumentException("Max tokens must be positive");
        }
        this.maxTokens = maxTokens;
    }
    
    public boolean isMaxTokensEnabled() {
        return maxTokensEnabled;
    }
    
    public void setMaxTokensEnabled(boolean enabled) {
        this.maxTokensEnabled = enabled;
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

    // === Temperature ===
    
    public double getTemperature() {
        return temperature;
    }
    
    public void setTemperature(double temperature) {
        if (temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("Temperature must be between 0 and 2");
        }
        this.temperature = temperature;
    }
    
    public boolean isTemperatureEnabled() {
        return temperatureEnabled;
    }
    
    public void setTemperatureEnabled(boolean enabled) {
        this.temperatureEnabled = enabled;
    }

    // === System Message ===
    
    public String getCurrentSystemMessage() {
        return currentSystemMessage;
    }
    
    public void setSystemMessage(String systemMessage) {
        if (systemMessage == null || systemMessage.isBlank()) {
            throw new IllegalArgumentException("System message cannot be empty");
        }
        this.currentSystemMessage = systemMessage;
        // Обновляем историю с новым системным сообщением
        conversationHistory.clear();
        conversationHistory.add(Message.system(currentSystemMessage));
    }

    public void setSystemMessage(int mode) {
        if (mode == 1) {
            setSystemMessage(SYSTEM_MESSAGE_TESTER);
        } else if (mode == 2) {
            setSystemMessage(SYSTEM_MESSAGE_HELPER);
        } else {
            throw new IllegalArgumentException("Mode must be 1 (Tester) or 2 (Helper)");
        }
    }

    /**
     * Исключение для ошибок API.
     */
    public static class ApiException extends RuntimeException {
        private final int statusCode;

        public ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
