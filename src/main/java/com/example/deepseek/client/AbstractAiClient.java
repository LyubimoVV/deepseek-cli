package com.example.deepseek.client;

import com.example.deepseek.agent.SummaryAgent;
import com.example.deepseek.context.ContextManager;
import com.example.deepseek.dto.LlmResponse;
import com.example.deepseek.dto.Message;
import com.example.deepseek.dto.RequestMetrics;
import com.example.deepseek.dto.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Базовый абстрактный класс для AI клиентов.
 * Содержит общую функциональность: управление историей разговора, настройки, метрики.
 */
public abstract class AbstractAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(AbstractAiClient.class);

    // Системные сообщения по умолчанию
    protected static final String DEFAULT_SYSTEM_MESSAGE = "Ты полезный помощник";
    protected static final String SYSTEM_MESSAGE_TESTER = "Ты senior тестировщик из Google с 10+ годами опыта. "
            + "Объясняй концепции тестирования простыми словами, как будто объясняешь джуниору на первом дне работы. "
            + "Используй практические примеры из реальной разработки. Отвечай кратко и структурированно.";

    // История разговора
    protected final List<Message> conversationHistory = new ArrayList<>();

    // Настройки
    protected String currentSystemMessage = DEFAULT_SYSTEM_MESSAGE;
    protected int maxTokens = 32000;
    protected double temperature = 1.0;

    // Метрики последнего запроса
    protected RequestMetrics lastMetrics = null;

    // Состояние настроек (включены/выключены)
    protected boolean maxTokensEnabled = false;
    protected boolean temperatureEnabled = false;

    // Управление контекстом
    protected ContextManager contextManager;
    protected SummaryAgent summaryAgent;
    protected long currentSessionId = -1;

    /**
     * Конструктор инициализирует историю разговора с системным сообщением.
     */
    protected AbstractAiClient() {
        resetConversationHistory();
    }

    /**
     * Конструктор с системным сообщением.
     */
    protected AbstractAiClient(String systemMessage) {
        this.currentSystemMessage = systemMessage != null && !systemMessage.isBlank()
                ? systemMessage
                : DEFAULT_SYSTEM_MESSAGE;
        resetConversationHistory();
    }

    /**
     * Конструктор с настройками.
     */
    protected AbstractAiClient(String systemMessage, int maxTokens, double temperature) {
        this.currentSystemMessage = systemMessage != null && !systemMessage.isBlank()
                ? systemMessage
                : DEFAULT_SYSTEM_MESSAGE;
        setMaxTokens(maxTokens);
        setTemperature(temperature);
        resetConversationHistory();
    }

    /**
     * Конструктор с контекст-менеджером.
     */
    protected AbstractAiClient(ContextManager contextManager, SummaryAgent summaryAgent) {
        this.contextManager = contextManager;
        this.summaryAgent = summaryAgent;
        resetConversationHistory();
    }

    /**
     * Конструктор с контекст-менеджером и системным сообщением.
     */
    protected AbstractAiClient(String systemMessage, ContextManager contextManager, SummaryAgent summaryAgent) {
        this.currentSystemMessage = systemMessage != null && !systemMessage.isBlank()
                ? systemMessage
                : DEFAULT_SYSTEM_MESSAGE;
        this.contextManager = contextManager;
        this.summaryAgent = summaryAgent;
        resetConversationHistory();
    }

    /**
     * Устанавливает ID текущей сессии для управления контекстом.
     */
    public void setCurrentSessionId(long sessionId) {
        log.info("setCurrentSessionId: sessionId={}, oldSessionId={}", sessionId, currentSessionId);
        this.currentSessionId = sessionId;
    }

    /**
     * Возвращает ID текущей сессии.
     */
    public long getCurrentSessionId() {
        return currentSessionId;
    }

    /**
     * Устанавливает контекст-менеджер для управления сжатием.
     */
    public void setContextManager(ContextManager contextManager) {
        this.contextManager = contextManager;
        log.info("setContextManager: contextManager set for client");
    }

    /**
     * Устанавливает агент для создания summary.
     */
    public void setSummaryAgent(SummaryAgent summaryAgent) {
        this.summaryAgent = summaryAgent;
        log.info("setSummaryAgent: summaryAgent set for client");
    }

    /**
     * Абстрактный метод для отправки запроса к API.
     * Должен быть реализован в подклассах.
     * @throws AiException если произошла ошибка при взаимодействии с API
     */
    protected abstract String sendApiRequest(String userMessage) throws AiException;

    /**
     * Абстрактный метод для отправки запроса к API с указанным списком сообщений.
     * Должен быть реализован в подклассах.
     * Используется для chatWithMessages - минует историю разговора и сжатие контекста.
     * @throws AiException если произошла ошибка при взаимодействии с API
     */
    protected abstract LlmResponse sendApiRequestWithMessages(List<Message> messages) throws AiException;

    @Override
    public LlmResponse chatWithMessages(List<Message> messages) throws AiException {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("Messages cannot be null or empty");
        }

        try {
            long startTime = System.currentTimeMillis();
            LlmResponse llmResponse = sendApiRequestWithMessages(messages);
            long latencyMs = System.currentTimeMillis() - startTime;

            if (lastMetrics != null) {
                lastMetrics = new RequestMetrics(
                    lastMetrics.getInputTokens(),
                    lastMetrics.getOutputTokens(),
                    lastMetrics.getTotalTokens(),
                    lastMetrics.getCachedTokens(),
                    latencyMs,
                    lastMetrics.getCostUsd(),
                    getCurrentModel()
                );
            }

            return llmResponse;
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Unexpected error in chatWithMessages: " + e.getMessage(), e);
        }
    }

    @Override
    public String chat(String userMessage) throws AiException {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("User message cannot be null or empty");
        }

        // Сохраняем пользовательское сообщение в историю
        conversationHistory.add(Message.user(userMessage));

        try {
            // Отправляем запрос к API
            long startTime = System.currentTimeMillis();
            String response = sendApiRequest(userMessage);
            long latencyMs = System.currentTimeMillis() - startTime;

            // Сохраняем ответ ассистента в историю
            conversationHistory.add(Message.assistant(response));

            // Обновляем метрики с новым временем отклика
            if (lastMetrics != null) {
                lastMetrics = new RequestMetrics(
                    lastMetrics.getInputTokens(),
                    lastMetrics.getOutputTokens(),
                    lastMetrics.getTotalTokens(),
                    lastMetrics.getCachedTokens(),
                    latencyMs,
                    lastMetrics.getCostUsd(),
                    getCurrentModel()
                );
            }

            return response;
        } catch (AiException e) {
            // Если произошла ошибка API, удаляем пользовательское сообщение из истории
            if (!conversationHistory.isEmpty() &&
                conversationHistory.get(conversationHistory.size() - 1).role().equals("user")) {
                conversationHistory.remove(conversationHistory.size() - 1);
            }
            throw e;
        } catch (Exception e) {
            // Для других исключений также удаляем сообщение и оборачиваем в AiException
            if (!conversationHistory.isEmpty() &&
                conversationHistory.get(conversationHistory.size() - 1).role().equals("user")) {
                conversationHistory.remove(conversationHistory.size() - 1);
            }
            throw new AiException("Unexpected error: " + e.getMessage(), e);
        }
    }

    @Override
    public RequestMetrics getLastMetrics() {
        return lastMetrics;
    }

    @Override
    public void clearHistory() {
        resetConversationHistory();
    }

    @Override
    public void setSystemMessage(String systemMessage) {
        if (systemMessage == null || systemMessage.isBlank()) {
            this.currentSystemMessage = DEFAULT_SYSTEM_MESSAGE;
        } else {
            this.currentSystemMessage = systemMessage;
        }
        resetConversationHistory();
    }

    @Override
    public String getCurrentSystemMessage() {
        return currentSystemMessage;
    }

    @Override
    public void setMaxTokens(int maxTokens) {
        if (maxTokens < 1 || maxTokens > 64000) {
            throw new IllegalArgumentException("Max tokens must be between 1 and 64000");
        }
        this.maxTokens = maxTokens;
    }

    @Override
    public int getMaxTokens() {
        return maxTokens;
    }

    /**
     * Включает или выключает ограничение максимального количества токенов.
     */
    public void setMaxTokensEnabled(boolean enabled) {
        this.maxTokensEnabled = enabled;
    }

    /**
     * Проверяет, включено ли ограничение максимального количества токенов.
     */
    public boolean isMaxTokensEnabled() {
        return maxTokensEnabled;
    }

    @Override
    public void setTemperature(double temperature) {
        if (temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("Temperature must be between 0 and 2");
        }
        this.temperature = temperature;
    }

    @Override
    public double getTemperature() {
        return temperature;
    }

    /**
     * Включает или выключает настройку температуры.
     */
    public void setTemperatureEnabled(boolean enabled) {
        this.temperatureEnabled = enabled;
    }

    /**
     * Проверяет, включена ли настройка температуры.
     */
    public boolean isTemperatureEnabled() {
        return temperatureEnabled;
    }

    @Override
    public List<Message> getConversationHistory() {
        return conversationHistory;
    }

    /**
     * Возвращает историю разговора для модификации.
     * Используется для восстановления сессий.
     */
    public List<Message> getConversationHistoryForRestore() {
        return conversationHistory;
    }

    /**
     * Возвращает копию истории разговора для конкретного запроса.
     * Включает системное сообщение и все предыдущие сообщения.
     */
    protected List<Message> getMessagesForRequest() {
        log.info("getMessagesForRequest: currentSessionId={}, contextManager={}, summaryAgent={}, conversationHistory={}",
            currentSessionId, contextManager != null, summaryAgent != null, conversationHistory.size());

        List<Message> messages;
        if (currentSessionId > 0 && contextManager != null && summaryAgent != null) {
            log.info("getMessagesForRequest: Using compressed context for sessionId={}", currentSessionId);
            messages = summaryAgent.getCompressedContext(currentSessionId, conversationHistory, currentSystemMessage);
        } else {
            log.info("getMessagesForRequest: Using full history (compression disabled or unavailable)");
            messages = new ArrayList<>(conversationHistory);
        }

        return messages;
    }

    /**
     * Сбрасывает историю разговора, оставляя только системное сообщение.
     */
    protected void resetConversationHistory() {
        conversationHistory.clear();
        conversationHistory.add(Message.system(currentSystemMessage));
    }

    /**
     * Устанавливает режим работы (1 = Tester, 2 = Helper).
     */
    public void setMode(int mode) {
        if (mode == 1) {
            setSystemMessage(SYSTEM_MESSAGE_TESTER);
        } else if (mode == 2) {
            setSystemMessage(DEFAULT_SYSTEM_MESSAGE);
        } else {
            throw new IllegalArgumentException("Mode must be 1 (Tester) or 2 (Helper)");
        }
    }

    /**
     * Обновляет метрики последнего запроса.
     */
    protected void updateLastMetrics(RequestMetrics metrics) {
        this.lastMetrics = metrics;
    }

    /**
     * Вычисляет стоимость запроса на основе модели и использованных токенов.
     */
    protected double calculateCost(int inputTokens, int outputTokens) {
        return PricingService.calculateCost(getCurrentModel(), inputTokens, outputTokens);
    }
}
