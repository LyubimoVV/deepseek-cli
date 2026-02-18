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
    private static final String SYSTEM_MESSAGE_HELPER = "Ты полезный помощник. ВАЖНО: Используй только обычный текст. Не используй никакие спецсимволы, LaTeX разметку, Markdown, звездочки, решетки или другое форматирование. Просто обычный текст.";
    private static final String SYSTEM_MESSAGE_TESTER = "Ты senior тестировщик из Google с 10+ годами опыта. Объясняй концепции тестирования простыми словами, как будто объясняешь джуниору на первом дне работы. Используй практические примеры из реальной разработки. Отвечай кратко и структурированно. ВАЖНО: Используй только обычный текст. Не используй никакие спецсимволы, LaTeX разметку, Markdown, звездочки, решетки или другое форматирование. Просто обычный текст.";

    // Настройки для ограниченных запросов
    private int maxTokens = 200;
    private List<String> stopSequences = List.of("\n\n");
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
     * Отправляет обычный запрос к API без ограничений.
     */
    public String chat(String userMessage) throws IOException {
        return sendRequest(userMessage, false);
    }

    /**
     * Отправляет ограниченный запрос к API с настройками форматирования.
     */
    public String chatLimited(String userMessage) throws IOException {
        return sendRequest(userMessage, true);
    }

    /**
     * Отправляет запрос к API и возвращает ответ.
     * @param userMessage сообщение пользователя
     * @param useLimitations использовать ли ограничения (max_tokens, stop, системное сообщение)
     */
    private String sendRequest(String userMessage, boolean useLimitations) throws IOException {
        // Создаем копию истории для данного запроса
        List<Message> messages = new ArrayList<>(conversationHistory);

        messages.add(Message.user(userMessage));

        ChatRequest request;
        if (useLimitations) {
            request = new ChatRequest(DEFAULT_MODEL, messages, maxTokens, stopSequences);
        } else {
            request = new ChatRequest(DEFAULT_MODEL, messages);
        }

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

        // Очищаем LaTeX разметку
        content = cleanLatex(content);

        // Добавляем в историю только если это не ограниченный запрос
        // или если хотим сохранять ограниченные ответы тоже
        conversationHistory.add(Message.user(userMessage));
        conversationHistory.add(Message.assistant(content));

        return content;
    }

    /**
     * Очищает историюconversation.
     */
    public void clearHistory() {
        conversationHistory.clear();
        this.conversationHistory.add(Message.system(currentSystemMessage));
    }

    // Геттеры и сеттеры для настроек ограниченного режима
    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        if (maxTokens < 1) {
            throw new IllegalArgumentException("Max tokens must be positive");
        }
        this.maxTokens = maxTokens;
    }

    public List<String> getStopSequences() {
        return new ArrayList<>(stopSequences);
    }

    public void setStopSequences(List<String> stopSequences) {
        if (stopSequences == null || stopSequences.isEmpty()) {
            throw new IllegalArgumentException("Stop sequences cannot be null or empty");
        }
        this.stopSequences = new ArrayList<>(stopSequences);
    }



    public String getCurrentSystemMessage() {
        return currentSystemMessage;
    }

    public void setSystemMessage(int mode) {
        if (mode == 1) {
            this.currentSystemMessage = SYSTEM_MESSAGE_TESTER;
        } else if (mode == 2) {
            this.currentSystemMessage = SYSTEM_MESSAGE_HELPER;
        } else {
            throw new IllegalArgumentException("Mode must be 1 (Tester) or 2 (Helper)");
        }
        // Обновляем историю с новым системным сообщением
        conversationHistory.clear();
        conversationHistory.add(Message.system(currentSystemMessage));
    }

    /**
     * Очищает LaTeX разметку из текста.
     */
    private String cleanLatex(String text) {
        if (text == null) {
            return null;
        }
        // Удаляем inline LaTeX: \( ... \)
        text = text.replaceAll("\\\\\\(", "");
        text = text.replaceAll("\\\\\\)", "");

        // Удаляем block LaTeX: \[ ... \]
        text = text.replaceAll("\\\\\\[", "");
        text = text.replaceAll("\\\\\\]", "");

        // Удаляем $ и $$
        text = text.replaceAll("\\$\\$", "");
        text = text.replaceAll("\\$", "");

        // Удаляем экранированные фигурные скобки
        text = text.replaceAll("\\\\\\{", "{");
        text = text.replaceAll("\\\\\\}", "}");

        return text;
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
