package com.example.deepseek.client;

import com.example.deepseek.dto.Message;
import com.example.deepseek.dto.RequestMetrics;

import java.util.List;

/**
 * Адаптер для DeepSeekClient, реализующий интерфейс AiClient.
 * Позволяет использовать DeepSeekClient через общий интерфейс.
 *
 * <p>После рефакторинга DeepSeekClient сам реализует AiClient,
 * но этот адаптер поддерживается для обратной совместимости.</p>
 */
public class DeepSeekClientAdapter implements AiClient {

    private final DeepSeekClient client;

    public DeepSeekClientAdapter(DeepSeekClient client) {
        if (client == null) {
            throw new IllegalArgumentException("Client cannot be null");
        }
        this.client = client;
    }

    /**
     * Создаёт адаптер с новым DeepSeekClient для указанной модели.
     */
    public DeepSeekClientAdapter(String apiKey, String model) {
        this(new DeepSeekClient(apiKey, model));
    }

    /**
     * Создаёт адаптер с новым DeepSeekClient для указанной модели и системного сообщения.
     */
    public DeepSeekClientAdapter(String apiKey, String model, String systemMessage) {
        this(new DeepSeekClient(apiKey, model, systemMessage));
    }

    @Override
    public String chat(String userMessage) throws AiException {
        return client.chat(userMessage);
    }

    @Override
    public RequestMetrics getLastMetrics() {
        return client.getLastMetrics();
    }

    @Override
    public String getCurrentModel() {
        return client.getCurrentModel();
    }

    @Override
    public String getModelDisplayName() {
        return PricingService.getModelDisplayName(client.getCurrentModel());
    }

    @Override
    public String getProviderName() {
        return "DeepSeek";
    }

    @Override
    public void clearHistory() {
        client.clearHistory();
    }

    @Override
    public void setSystemMessage(String systemMessage) {
        client.setSystemMessage(systemMessage);
    }

    @Override
    public String getCurrentSystemMessage() {
        return client.getCurrentSystemMessage();
    }

    @Override
    public void setMaxTokens(int maxTokens) {
        client.setMaxTokens(maxTokens);
    }

    @Override
    public int getMaxTokens() {
        return client.getMaxTokens();
    }

    @Override
    public void setMaxTokensEnabled(boolean enabled) {
        client.setMaxTokensEnabled(enabled);
    }

    @Override
    public boolean isMaxTokensEnabled() {
        return client.isMaxTokensEnabled();
    }

    @Override
    public void setTemperature(double temperature) {
        client.setTemperature(temperature);
    }

    @Override
    public double getTemperature() {
        return client.getTemperature();
    }

    @Override
    public void setTemperatureEnabled(boolean enabled) {
        client.setTemperatureEnabled(enabled);
    }

    @Override
    public boolean isTemperatureEnabled() {
        return client.isTemperatureEnabled();
    }

    @Override
    public List<Message> getConversationHistory() {
        return client.getConversationHistory();
    }

    @Override
    public List<Message> getConversationHistoryForRestore() {
        return client.getConversationHistoryForRestore();
    }

    /**
     * Возвращает оригинальный DeepSeekClient.
     */
    public DeepSeekClient getDelegate() {
        return client;
    }
}
