package com.example.deepseek.dto;

/**
 * Метрики выполненного запроса к DeepSeek API.
 * Содержит информацию о токенах, времени ответа и стоимости.
 */
public class RequestMetrics {
    
    private final int inputTokens;
    private final int outputTokens;
    private final int totalTokens;
    private final int cachedTokens;
    private final long latencyMs;
    private final double costUsd;
    private final String model;
    
    public RequestMetrics(int inputTokens, int outputTokens, int totalTokens, int cachedTokens,
                          long latencyMs, double costUsd, String model) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.cachedTokens = cachedTokens;
        this.latencyMs = latencyMs;
        this.costUsd = costUsd;
        this.model = model;
    }
    
    /**
     * Создаёт пустые метрики с нулевыми значениями.
     */
    public static RequestMetrics empty() {
        return new RequestMetrics(0, 0, 0, 0, 0, 0.0, "");
    }
    
    // === Getters ===
    
    public int getInputTokens() {
        return inputTokens;
    }
    
    public int getOutputTokens() {
        return outputTokens;
    }
    
    public int getTotalTokens() {
        return totalTokens;
    }
    
    public int getCachedTokens() {
        return cachedTokens;
    }
    
    public long getLatencyMs() {
        return latencyMs;
    }
    
    public double getCostUsd() {
        return costUsd;
    }
    
    public String getModel() {
        return model;
    }
    
    // === Форматирование ===
    
    /**
     * Возвращает стоимость в формате "$X.XXXXXX".
     */
    public String getFormattedCost() {
        return String.format("$%.6f", costUsd);
    }
    
    /**
     * Возвращает время ответа в читаемом формате.
     */
    public String getFormattedLatency() {
        if (latencyMs < 1000) {
            return latencyMs + " ms";
        }
        return String.format("%.2f sec", latencyMs / 1000.0);
    }
    
    @Override
    public String toString() {
        return String.format(
            "RequestMetrics{input=%d, output=%d, total=%d, cached=%d, latency=%dms, cost=%s, model='%s'}",
            inputTokens, outputTokens, totalTokens, cachedTokens, latencyMs, getFormattedCost(), model
        );
    }
}
