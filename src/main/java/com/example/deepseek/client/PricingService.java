package com.example.deepseek.client;

/**
 * Сервис для расчёта стоимости запросов к AI API.
 * 
 * Цены указаны за 1 миллион токенов.
 * 
 * DeepSeek API:
 * - deepseek-chat: Input $0.28, Output $0.42 (cache miss), Input $0.028 (cache hit)
 * - deepseek-reasoner: Input $0.28, Output $0.42 (cache miss), Input $0.028 (cache hit)
 * 
 * OpenRouter (бесплатные модели):
 * - Все бесплатные модели (:free) имеют стоимость $0
 */
public class PricingService {
    
    // DeepSeek тарифы (за 1 миллион токенов)
    private static final double DEEPSEEK_CHAT_INPUT = 0.28;
    private static final double DEEPSEEK_CHAT_OUTPUT = 0.42;
    private static final double DEEPSEEK_REASONER_INPUT = 0.28;
    private static final double DEEPSEEK_REASONER_OUTPUT = 0.42;
    
    // OpenRouter бесплатные модели - $0
    private static final double OPENROUTER_FREE_PRICE = 0.0;
    
    // Ollama локальные модели - бесплатно
    private static final double OLLAMA_PRICE = 0.0;
    
    /**
     * Рассчитывает стоимость запроса в долларах США.
     * 
     * @param model         название модели
     * @param inputTokens   количество входных токенов
     * @param outputTokens  количество выходных токенов
     * @return стоимость в USD
     */
    public static double calculateCost(String model, int inputTokens, int outputTokens) {
        if (model == null) {
            return 0.0;
        }
        
        // Бесплатные модели OpenRouter
        if (isOpenRouterModel(model)) {
            return OPENROUTER_FREE_PRICE;
        }
        
        // Ollama локальные модели - бесплатно
        if (isOllamaModel(model)) {
            return OLLAMA_PRICE;
        }
        
        double inputPricePerToken;
        double outputPricePerToken;
        
        switch (model) {
            case DeepSeekClient.MODEL_CHAT:
                inputPricePerToken = DEEPSEEK_CHAT_INPUT / 1_000_000;
                outputPricePerToken = DEEPSEEK_CHAT_OUTPUT / 1_000_000;
                break;
            case DeepSeekClient.MODEL_REASONER:
                inputPricePerToken = DEEPSEEK_REASONER_INPUT / 1_000_000;
                outputPricePerToken = DEEPSEEK_REASONER_OUTPUT / 1_000_000;
                break;
            default:
                return 0.0;
        }
        
        return (inputTokens * inputPricePerToken) + (outputTokens * outputPricePerToken);
    }
    
    /**
     * Проверяет, является ли модель моделью OpenRouter.
     */
    public static boolean isOpenRouterModel(String model) {
        if (model == null) {
            return false;
        }
        // OpenRouter модели имеют формат "provider/model-name"
        return model.contains("/") || 
               model.equals(OpenRouterClient.MODEL_GPT_OSS) ||
               model.equals(OpenRouterClient.MODEL_LFM_2_5);
    }
    
    /**
     * Проверяет, является ли модель локальной Ollama моделью.
     */
    public static boolean isOllamaModel(String model) {
        if (model == null) {
            return false;
        }
        return model.startsWith("ollama:");
    }
    
    /**
     * Извлекает имя Ollama модели из полного идентификатора.
     */
    public static String extractOllamaModelName(String model) {
        if (model == null || !model.startsWith("ollama:")) {
            return model;
        }
        return model.substring(7);
    }
    
    /**
     * Возвращает цену за 1M входных токенов для указанной модели.
     */
    public static double getInputPricePerMillion(String model) {
        if (model == null) {
            return 0.0;
        }
        
        // Бесплатные модели OpenRouter
        if (isOpenRouterModel(model)) {
            return OPENROUTER_FREE_PRICE;
        }
        
        // Ollama локальные модели
        if (isOllamaModel(model)) {
            return OLLAMA_PRICE;
        }
        
        switch (model) {
            case DeepSeekClient.MODEL_CHAT:
                return DEEPSEEK_CHAT_INPUT;
            case DeepSeekClient.MODEL_REASONER:
                return DEEPSEEK_REASONER_INPUT;
            default:
                return 0.0;
        }
    }
    
    /**
     * Возвращает цену за 1M выходных токенов для указанной модели.
     */
    public static double getOutputPricePerMillion(String model) {
        if (model == null) {
            return 0.0;
        }
        
        // Бесплатные модели OpenRouter
        if (isOpenRouterModel(model)) {
            return OPENROUTER_FREE_PRICE;
        }
        
        // Ollama локальные модели
        if (isOllamaModel(model)) {
            return OLLAMA_PRICE;
        }
        
        switch (model) {
            case DeepSeekClient.MODEL_CHAT:
                return DEEPSEEK_CHAT_OUTPUT;
            case DeepSeekClient.MODEL_REASONER:
                return DEEPSEEK_REASONER_OUTPUT;
            default:
                return 0.0;
        }
    }
    
    /**
     * Возвращает отображаемое название модели.
     */
    public static String getModelDisplayName(String model) {
        if (model == null) {
            return "Unknown";
        }
        
        switch (model) {
            case DeepSeekClient.MODEL_CHAT:
                return "DeepSeek Chat";
            case DeepSeekClient.MODEL_REASONER:
                return "DeepSeek Reasoner";
            case OpenRouterClient.MODEL_GPT_OSS:
                return "GPT-OSS 20B (Free)";
            case OpenRouterClient.MODEL_LFM_2_5:
                return "LFM 2.5 1.2B (Free)";
            default:
                // Ollama модели
                if (isOllamaModel(model)) {
                    return extractOllamaModelName(model) + " (Local)";
                }
                // Для других OpenRouter моделей показываем имя после последнего /
                if (model.contains("/")) {
                    String[] parts = model.split("/");
                    String name = parts[parts.length - 1];
                    // Добавляем (Free) если модель бесплатная
                    if (model.endsWith(":free")) {
                        return name.substring(0, name.length() - 5) + " (Free)";
                    }
                    return name;
                }
                return model;
        }
    }
    
    /**
     * Возвращает название провайдера для модели.
     */
    public static String getProviderName(String model) {
        if (model == null) {
            return "Unknown";
        }
        
        switch (model) {
            case DeepSeekClient.MODEL_CHAT:
            case DeepSeekClient.MODEL_REASONER:
                return "DeepSeek";
            default:
                if (isOllamaModel(model)) {
                    return "Ollama";
                }
                if (isOpenRouterModel(model)) {
                    return "OpenRouter";
                }
                return "Unknown";
        }
    }
    
    /**
     * Форматирует цену в читаемый вид.
     */
    public static String formatPrice(double pricePerMillion) {
        if (pricePerMillion == 0.0) {
            return "FREE";
        }
        return String.format("$%.2f/1M", pricePerMillion);
    }
    
    /**
     * Возвращает отформатированную стоимость для модели.
     */
    public static String getFormattedCost(String model) {
        double cost = getInputPricePerMillion(model);
        return formatPrice(cost);
    }
    
    /**
     * Возвращает все доступные модели.
     */
    public static String[] getAvailableModels() {
        return new String[] {
            DeepSeekClient.MODEL_REASONER,
            DeepSeekClient.MODEL_CHAT,
            OpenRouterClient.MODEL_GPT_OSS,
            OpenRouterClient.MODEL_LFM_2_5
        };
    }
    
    /**
     * Возвращает модели DeepSeek.
     */
    public static String[] getDeepSeekModels() {
        return new String[] {
            DeepSeekClient.MODEL_REASONER,
            DeepSeekClient.MODEL_CHAT
        };
    }
    
    /**
     * Возвращает бесплатные модели OpenRouter.
     */
    public static String[] getOpenRouterModels() {
        return new String[] {
            OpenRouterClient.MODEL_GPT_OSS,
            OpenRouterClient.MODEL_LFM_2_5
        };
    }
}
