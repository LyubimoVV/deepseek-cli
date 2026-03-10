package com.example.deepseek.client;

import com.example.deepseek.agent.SummaryAgent;
import com.example.deepseek.context.ContextManager;
import com.example.deepseek.dto.RequestMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Менеджер для управления клиентами различных AI провайдеров.
 * Позволяет переключаться между моделями и сравнивать ответы.
 */
public class ClientManager {

    private static final Logger log = LoggerFactory.getLogger(ClientManager.class);

    private final Map<String, AiClient> clients = new HashMap<>();
    private String currentModel;
    private String systemMessage = "Ты полезный помощник";
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final ContextManager contextManager;
    private final SummaryAgent summaryAgent;

    /**
     * Конструктор по умолчанию.
     */
    public ClientManager() {
        this.contextManager = null;
        this.summaryAgent = null;
    }

    /**
     * Конструктор с контекст-менеджером для управления сжатием истории.
     */
    public ClientManager(ContextManager contextManager, SummaryAgent summaryAgent) {
        this.contextManager = contextManager;
        this.summaryAgent = summaryAgent;
    }

    // Системные сообщения
    private static final String SYSTEM_MESSAGE_HELPER = "Ты полезный помощник";
    private static final String SYSTEM_MESSAGE_TESTER = "Ты senior тестировщик из Google с 10+ годами опыта. Объясняй концепции тестирования простыми словами, как будто объясняешь джуниору на первом дне работы. Используй практические примеры из реальной разработки. Отвечай кратко и структурированно.";

    /**
     * Регистрирует клиент для модели.
     */
    public void registerClient(String model, AiClient client) {
        clients.put(model, client);
        if (currentModel == null) {
            currentModel = model;
        }
    }

    /**
     * Возвращает клиент для указанной модели.
     */
    public AiClient getClient(String model) {
        return clients.get(model);
    }

    /**
     * Возвращает текущий клиент.
     */
    public AiClient getCurrentClient() {
        return clients.get(currentModel);
    }

    /**
     * Переключает текущую модель.
     */
    public void setCurrentModel(String model) {
        if (!clients.containsKey(model)) {
            throw new IllegalArgumentException("Model not registered: " + model);
        }
        this.currentModel = model;
    }

    /**
     * Возвращает текущую модель.
     */
    public String getCurrentModel() {
        return currentModel;
    }

    /**
     * Отправляет запрос к текущей модели.
     */
    public String chat(String userMessage) throws AiException {
        AiClient client = getCurrentClient();
        if (client == null) {
            throw new IllegalStateException("No client available for model: " + currentModel);
        }
        return client.chat(userMessage);
    }

    /**
     * Отправляет запрос к текущей модели с указанием ID сессии.
     */
    public String chat(long sessionId, String userMessage) throws AiException {
        AiClient client = getCurrentClient();
        if (client == null) {
            throw new IllegalStateException("No client available for model: " + currentModel);
        }

        log.info("ClientManager.chat: sessionId={}, clientClass={}, currentModel={}",
            sessionId, client.getClass().getName(), currentModel);

        if (client instanceof AbstractAiClient) {
            log.info("ClientManager.chat: client is AbstractAiClient, setting sessionId={}", sessionId);
            ((AbstractAiClient) client).setCurrentSessionId(sessionId);
        } else {
            log.warn("ClientManager.chat: client is NOT AbstractAiClient, compression will NOT be used!");
        }

        return client.chat(userMessage);
    }

    /**
     * Устанавливает системное сообщение для всех клиентов.
     */
    public void setSystemMessage(String systemMessage) {
        this.systemMessage = systemMessage;
        for (AiClient client : clients.values()) {
            client.setSystemMessage(systemMessage);
        }
    }

    /**
     * Устанавливает режим работы (1 = Tester, 2 = Helper).
     */
    public void setMode(int mode) {
        if (mode == 1) {
            setSystemMessage(SYSTEM_MESSAGE_TESTER);
        } else if (mode == 2) {
            setSystemMessage(SYSTEM_MESSAGE_HELPER);
        } else {
            throw new IllegalArgumentException("Mode must be 1 (Tester) or 2 (Helper)");
        }
    }

    /**
     * Возвращает текущее системное сообщение.
     */
    public String getSystemMessage() {
        return systemMessage;
    }

    /**
     * Очищает историю для всех клиентов.
     */
    public void clearAllHistory() {
        for (AiClient client : clients.values()) {
            client.clearHistory();
        }
    }

    /**
     * Возвращает метрики последнего запроса текущего клиента.
     */
    public RequestMetrics getLastMetrics() {
        AiClient client = getCurrentClient();
        return client != null ? client.getLastMetrics() : null;
    }

    /**
     * Возвращает список зарегистрированных моделей.
     */
    public List<String> getAvailableModels() {
        return new ArrayList<>(clients.keySet());
    }

    /**
     * Проверяет, есть ли клиент для модели.
     */
    public boolean hasClient(String model) {
        return clients.containsKey(model);
    }

    /**
     * Инициализирует контекст-менеджер и агента для всех AbstractAiClient клиентов.
     * Должен вызываться после регистрации всех клиентов.
     */
    public void initializeContextManager(ContextManager contextManager, SummaryAgent summaryAgent) {
        log.info("initializeContextManager: Setting up compression for all AbstractAiClient clients");

        for (Map.Entry<String, AiClient> entry : clients.entrySet()) {
            String model = entry.getKey();
            AiClient client = entry.getValue();

            if (client instanceof AbstractAiClient) {
                log.info("initializeContextManager: Setting contextManager/summaryAgent for model={}", model);
                ((AbstractAiClient) client).setContextManager(contextManager);
                ((AbstractAiClient) client).setSummaryAgent(summaryAgent);
            } else {
                log.warn("initializeContextManager: Client {} is not AbstractAiClient, compression will not work", model);
            }
        }

        log.info("initializeContextManager: Initialization completed for {} clients", clients.size());
    }

    /**
     * Включает или выключает thinking mode для DeepSeek Reasoner.
     */
    public void setThinkingEnabled(boolean enabled) {
        AiClient client = getCurrentClient();
        if (client instanceof DeepSeekClient) {
            ((DeepSeekClient) client).setThinkingEnabled(enabled);
        }
    }

    /**
     * Проверяет, включён ли thinking mode для текущего клиента.
     */
    public boolean isThinkingEnabled() {
        AiClient client = getCurrentClient();
        if (client instanceof DeepSeekClient) {
            return ((DeepSeekClient) client).isThinkingEnabled();
        }
        return false;
    }

    /**
     * Проверяет, поддерживает ли текущая модель thinking mode.
     */
    public boolean supportsThinking() {
        return DeepSeekClient.MODEL_REASONER.equals(currentModel);
    }
}
