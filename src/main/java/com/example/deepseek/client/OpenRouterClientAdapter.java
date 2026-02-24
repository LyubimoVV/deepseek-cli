package com.example.deepseek.client;

import com.example.deepseek.dto.Message;
import com.example.deepseek.dto.RequestMetrics;

import java.util.List;

/**
 * Адаптер для OpenRouterClient, реализующий интерфейс AiClient.
 * Предоставляет унифицированный интерфейс для работы с OpenRouter.
 */
public class OpenRouterClientAdapter implements AiClient {
    
    private final OpenRouterClient client;
    
    public OpenRouterClientAdapter(String apiKey, String model) {
        this.client = new OpenRouterClient(apiKey, model);
    }
    
    public OpenRouterClientAdapter(String apiKey, String model, String systemMessage) {
        this.client = new OpenRouterClient(apiKey, model, systemMessage);
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
        return client.getModelDisplayName();
    }
    
    @Override
    public String getProviderName() {
        return client.getProviderName();
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
     * Устанавливает модель.
     */
    public void setCurrentModel(String model) {
        client.setCurrentModel(model);
    }
}
