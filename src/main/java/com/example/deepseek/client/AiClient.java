package com.example.deepseek.client;

import com.example.deepseek.dto.LlmResponse;
import com.example.deepseek.dto.Message;
import com.example.deepseek.dto.RequestMetrics;

import java.util.List;

/**
 * Интерфейс для клиентов различных AI провайдеров.
 */
public interface AiClient {

    /**
     * Отправляет запрос к API и возвращает ответ.
     * @throws AiException если произошла ошибка при взаимодействии с API
     */
    String chat(String userMessage) throws AiException;

    /**
     * Возвращает метрики последнего запроса.
     */
    RequestMetrics getLastMetrics();

    /**
     * Возвращает название текущей модели.
     */
    String getCurrentModel();

    /**
     * Возвращает отображаемое название модели.
     */
    String getModelDisplayName();

    /**
     * Возвращает название провайдера.
     */
    String getProviderName();

    /**
     * Очищает историю разговора.
     */
    void clearHistory();

    /**
     * Устанавливает системное сообщение.
     */
    void setSystemMessage(String systemMessage);

    /**
     * Возвращает текущее системное сообщение.
     */
    String getCurrentSystemMessage();

    /**
     * Устанавливает максимальное количество токенов.
     */
    void setMaxTokens(int maxTokens);

    /**
     * Возвращает максимальное количество токенов.
     */
    int getMaxTokens();

    /**
     * Включает или выключает ограничение максимального количества токенов.
     */
    void setMaxTokensEnabled(boolean enabled);

    /**
     * Проверяет, включено ли ограничение максимального количества токенов.
     */
    boolean isMaxTokensEnabled();

    /**
     * Устанавливает температуру.
     */
    void setTemperature(double temperature);

    /**
     * Возвращает температуру.
     */
    double getTemperature();

    /**
     * Включает или выключает настройку температуры.
     */
    void setTemperatureEnabled(boolean enabled);

    /**
     * Проверяет, включена ли настройка температуры.
     */
    boolean isTemperatureEnabled();

    /**
     * Устанавливает стоп-последовательности.
     */
    void setStopSequences(List<String> stopSequences);

    /**
     * Возвращает стоп-последовательности.
     */
    List<String> getStopSequences();

    /**
     * Включает или выключает стоп-последовательности.
     */
    void setStopSequencesEnabled(boolean enabled);

    /**
     * Проверяет, включены ли стоп-последовательности.
     */
    boolean isStopSequencesEnabled();

    /**
     * Возвращает историю разговора.
     */
    List<Message> getConversationHistory();

    /**
     * Возвращает историю разговора для модификации (восстановления сессий).
     */
    List<Message> getConversationHistoryForRestore();

    /**
     * Отправляет запрос к API с указанным списком сообщений и возвращает ответ с токенами.
     */
    LlmResponse chatWithMessages(List<Message> messages) throws AiException;
}
