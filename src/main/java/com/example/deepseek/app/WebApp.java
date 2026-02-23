package com.example.deepseek.app;

import com.example.deepseek.client.*;
import com.example.deepseek.dto.Message;
import com.example.deepseek.dto.RequestMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Веб-приложение для DeepSeek CLI с интерфейсом в браузере.
 * Использует Javalin - легковесный веб-фреймворк.
 */
public class WebApp {

    private static final int DEFAULT_PORT = 8080;
    private static final String DEEPSEEK_API_KEY_ENV = "DEEPSEEK_API_KEY";
    private static final String OPENROUTER_API_KEY_ENV = "OPENROUTER_API_KEY";

    private static ClientManager clientManager;
    private static ObjectMapper objectMapper = new ObjectMapper();
    private static List<ChatMessage> chatHistory = new ArrayList<>();
    private static int currentMode = 2; // 1 = Tester, 2 = Helper

    // Режим сравнения моделей
    private static boolean compareMode = false;
    private static List<String> compareModels = new ArrayList<>();

    public static void main(String[] args) {
        // Инициализация ClientManager
        clientManager = new ClientManager();

        // Загрузка API ключей и регистрация клиентов
        String deepSeekApiKey = System.getenv(DEEPSEEK_API_KEY_ENV);
        String openRouterApiKey = System.getenv(OPENROUTER_API_KEY_ENV);

        boolean hasDeepSeek = deepSeekApiKey != null && !deepSeekApiKey.isBlank();
        boolean hasOpenRouter = openRouterApiKey != null && !openRouterApiKey.isBlank();

        if (!hasDeepSeek && !hasOpenRouter) {
            System.err.println("Ошибка: Не установлена ни одна переменная окружения для API ключей");
            System.err.println("Установите хотя бы один API ключ:");
            System.err.println("  set " + DEEPSEEK_API_KEY_ENV + "=your_deepseek_api_key");
            System.err.println("  или");
            System.err.println("  set " + OPENROUTER_API_KEY_ENV + "=your_openrouter_api_key");
            System.exit(1);
        }

        // Регистрируем клиентов DeepSeek
        if (hasDeepSeek) {
            System.out.println("✓ DeepSeek API ключ найден");
            clientManager.registerClient(DeepSeekClient.MODEL_CHAT,
                new DeepSeekClientAdapter(deepSeekApiKey, DeepSeekClient.MODEL_CHAT));
            clientManager.registerClient(DeepSeekClient.MODEL_REASONER,
                new DeepSeekClientAdapter(deepSeekApiKey, DeepSeekClient.MODEL_REASONER));
        }

        // Регистрируем клиентов OpenRouter
        if (hasOpenRouter) {
            System.out.println("✓ OpenRouter API ключ найден");
            clientManager.registerClient(OpenRouterClient.MODEL_GPT_OSS,
                new OpenRouterClientAdapter(openRouterApiKey, OpenRouterClient.MODEL_GPT_OSS));
            clientManager.registerClient(OpenRouterClient.MODEL_LFM_2_5,
                new OpenRouterClientAdapter(openRouterApiKey, OpenRouterClient.MODEL_LFM_2_5));
        } else {
            System.out.println("⚠ OpenRouter API ключ не найден. Установите " + OPENROUTER_API_KEY_ENV);
        }

        // Устанавливаем модель по умолчанию
        String defaultModel = hasDeepSeek ? DeepSeekClient.MODEL_REASONER : OpenRouterClient.MODEL_GPT_OSS;
        clientManager.setCurrentModel(defaultModel);

        // Инициализируем модели для сравнения
        if (hasDeepSeek) {
            compareModels.add(DeepSeekClient.MODEL_REASONER);
            compareModels.add(DeepSeekClient.MODEL_CHAT);
        }
        if (hasOpenRouter) {
            compareModels.add(OpenRouterClient.MODEL_GPT_OSS);
            compareModels.add(OpenRouterClient.MODEL_LFM_2_5);
        }

        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("Неверный порт, использую стандартный: " + DEFAULT_PORT);
            }
        }

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/static");
        });

        // API endpoints
        app.post("/api/chat", WebApp::handleChat);
        app.post("/api/clear", WebApp::handleClear);
        app.get("/api/mode", WebApp::handleGetMode);
        app.post("/api/mode", WebApp::handleSetMode);
        app.get("/api/model", WebApp::handleGetModel);
        app.post("/api/model", WebApp::handleSetModel);
        app.get("/api/history", WebApp::handleHistory);
        app.get("/api/info", WebApp::handleInfo);
        app.get("/api/system", WebApp::handleSystem);
        app.post("/api/limited", WebApp::handleLimited);
        app.get("/api/settings", WebApp::handleGetSettings);
        app.post("/api/settings", WebApp::handleSetSettings);

        // Новые endpoints для провайдеров и сравнения
        app.get("/api/providers", WebApp::handleGetProviders);
        app.get("/api/models", WebApp::handleGetModels);
        app.get("/api/compare/status", WebApp::handleGetCompareStatus);
        app.post("/api/compare/toggle", WebApp::handleToggleCompare);
        app.post("/api/compare/models", WebApp::handleSetCompareModels);
        app.post("/api/compare/chat", WebApp::handleCompareChat);

        // Endpoint для thinking mode
        app.get("/api/thinking", WebApp::handleGetThinking);
        app.post("/api/thinking", WebApp::handleSetThinking);

        app.start(port);

        String url = "http://localhost:" + port;
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║           AI Chat Interface - Запущен!                   ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.println("║  Откройте в браузере: " + url + "              ║");
        System.out.println("║  Нажмите Ctrl+C для остановки сервера                    ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");

        // Открываем браузер
        openBrowser(url);
    }

    private static void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;

            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", "start", "chrome", url);
                try {
                    pb.start();
                } catch (java.io.IOException e) {
                    pb = new ProcessBuilder("cmd", "/c", "start", url);
                    pb.start();
                }
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", "-a", "Google Chrome", url);
                try {
                    pb.start();
                } catch (java.io.IOException e) {
                    pb = new ProcessBuilder("open", url);
                    pb.start();
                }
            } else {
                pb = new ProcessBuilder("xdg-open", url);
                pb.start();
            }
        } catch (Exception e) {
            System.out.println("Не удалось открыть браузер автоматически. Откройте: " + url);
        }
    }

    // ==================== API HANDLERS ====================

    private static void handleChat(Context ctx) throws Exception {
        Map<String, String> request = ctx.bodyAsClass(Map.class);
        String message = request.get("message");

        if (message == null || message.isBlank()) {
            ctx.status(400).json(Map.of("success", false, "error", "Сообщение не может быть пустым"));
            return;
        }

        try {
            String response = clientManager.chat(message);
            var metrics = clientManager.getLastMetrics();

            chatHistory.add(new ChatMessage("user", message));
            chatHistory.add(new ChatMessage("assistant", response));

            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("response", response);
            responseMap.put("success", true);

            if (metrics != null) {
                responseMap.put("metrics", buildMetricsMap(metrics));
            }

            ctx.json(responseMap);
        } catch (Exception e) {
            e.printStackTrace();
            ctx.status(500).json(Map.of("success", false, "error", "Ошибка: " + e.getMessage()));
        }
    }

    /**
     * Обработчик для сравнения моделей - отправляет запрос к нескольким моделям параллельно.
     */
    private static void handleCompareChat(Context ctx) throws Exception {
        Map<String, Object> request = ctx.bodyAsClass(Map.class);
        String message = (String) request.get("message");

        @SuppressWarnings("unchecked")
        List<String> models = (List<String>) request.get("models");

        if (message == null || message.isBlank()) {
            ctx.status(400).json(Map.of("success", false, "error", "Сообщение не может быть пустым"));
            return;
        }

        if (models == null || models.isEmpty()) {
            models = compareModels;
        }

        // Фильтруем только доступные модели
        List<String> availableModels = new ArrayList<>();
        for (String model : models) {
            if (clientManager.hasClient(model)) {
                availableModels.add(model);
            }
        }

        if (availableModels.isEmpty()) {
            ctx.status(400).json(Map.of("success", false, "error", "Нет доступных моделей для сравнения"));
            return;
        }

        try {
            // Отправляем запросы параллельно
            Map<String, ClientManager.ModelResponse> responses = clientManager.chatSelectedModels(message, availableModels);

            // Добавляем в историю только от текущей модели
            chatHistory.add(new ChatMessage("user", message));

            // Формируем ответ
            List<Map<String, Object>> resultsList = new ArrayList<>();
            for (Map.Entry<String, ClientManager.ModelResponse> entry : responses.entrySet()) {
                Map<String, Object> result = new HashMap<>();
                ClientManager.ModelResponse mr = entry.getValue();
                result.put("model", mr.getModel());
                result.put("modelDisplayName", mr.getModelDisplayName());

                if (mr.isSuccess()) {
                    result.put("success", true);
                    result.put("response", mr.getResponse());
                    result.put("latencyMs", mr.getLatencyMs());

                    if (mr.getMetrics() != null) {
                        result.put("metrics", buildMetricsMap(mr.getMetrics()));
                    }
                } else {
                    result.put("success", false);
                    result.put("error", mr.getError());
                }

                resultsList.add(result);
            }

            // Добавляем первый успешный ответ в историю
            for (ClientManager.ModelResponse mr : responses.values()) {
                if (mr.isSuccess()) {
                    chatHistory.add(new ChatMessage("assistant", "[" + mr.getModelDisplayName() + "] " + mr.getResponse()));
                    break;
                }
            }

            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("success", true);
            responseMap.put("results", resultsList);
            responseMap.put("compareMode", true);

            ctx.json(responseMap);
        } catch (Exception e) {
            e.printStackTrace();
            ctx.status(500).json(Map.of("success", false, "error", "Ошибка: " + e.getMessage()));
        }
    }

    private static Map<String, Object> buildMetricsMap(RequestMetrics metrics) {
        Map<String, Object> metricsMap = new HashMap<>();
        metricsMap.put("inputTokens", metrics.getInputTokens());
        metricsMap.put("outputTokens", metrics.getOutputTokens());
        metricsMap.put("totalTokens", metrics.getTotalTokens());
        metricsMap.put("latencyMs", metrics.getLatencyMs());
        metricsMap.put("costUsd", metrics.getCostUsd());
        metricsMap.put("formattedCost", metrics.getFormattedCost());
        metricsMap.put("formattedLatency", metrics.getFormattedLatency());
        metricsMap.put("model", metrics.getModel());
        return metricsMap;
    }

    private static void handleClear(Context ctx) {
        chatHistory.clear();
        clientManager.clearAllHistory();
        ctx.json(Map.of("success", true, "message", "История очищена"));
    }

    private static void handleGetMode(Context ctx) {
        ctx.json(Map.of(
            "mode", currentMode,
            "modeName", currentMode == 1 ? "Тестировщик" : "Помощник"
        ));
    }

    private static void handleSetMode(Context ctx) throws Exception {
        Map<String, Integer> request = ctx.bodyAsClass(Map.class);
        Integer mode = request.get("mode");

        if (mode == null || (mode != 1 && mode != 2)) {
            ctx.status(400).json(Map.of("success", false, "error", "Режим должен быть 1 (Tester) или 2 (Helper)"));
            return;
        }

        currentMode = mode;
        clientManager.setMode(mode);
        clientManager.clearAllHistory();
        chatHistory.clear();

        ctx.json(Map.of(
            "success", true,
            "mode", currentMode,
            "modeName", currentMode == 1 ? "Тестировщик" : "Помощник",
            "message", "Режим изменён, история очищена"
        ));
    }

    private static void handleGetModel(Context ctx) {
        String model = clientManager.getCurrentModel();
        ctx.json(Map.of(
            "model", model,
            "modelName", PricingService.getModelDisplayName(model),
            "provider", PricingService.getProviderName(model)
        ));
    }

    private static void handleSetModel(Context ctx) throws Exception {
        Map<String, String> request = ctx.bodyAsClass(Map.class);
        String model = request.get("model");

        System.out.println("Запрос на смену модели: " + model);
        System.out.println("Доступные модели: " + clientManager.getAvailableModels());

        if (model == null || !clientManager.hasClient(model)) {
            String errorMsg = "Модель не найдена или недоступна: " + model;
            if (model != null && model.contains("/")) {
                errorMsg += ". Установите переменную окружения OPENROUTER_API_KEY";
            }
            ctx.status(400).json(Map.of("success", false, "error", errorMsg));
            return;
        }

        // Отключаем режим сравнения при смене модели
        compareMode = false;

        clientManager.setCurrentModel(model);
        System.out.println("Модель успешно изменена на: " + model);
        ctx.json(Map.of(
            "success", true,
            "model", model,
            "modelName", PricingService.getModelDisplayName(model),
            "provider", PricingService.getProviderName(model),
            "compareMode", compareMode,
            "message", "Модель изменена на " + PricingService.getModelDisplayName(model)
        ));
    }

    /**
     * Возвращает список доступных провайдеров.
     */
    private static void handleGetProviders(Context ctx) {
        List<Map<String, Object>> providers = new ArrayList<>();

        // Проверяем какие провайдеры доступны
        boolean hasDeepSeek = false;
        boolean hasOpenRouter = false;

        for (String model : clientManager.getAvailableModels()) {
            String provider = PricingService.getProviderName(model);
            if ("DeepSeek".equals(provider)) hasDeepSeek = true;
            if ("OpenRouter".equals(provider)) hasOpenRouter = true;
        }

        if (hasDeepSeek) {
            Map<String, Object> deepSeek = new HashMap<>();
            deepSeek.put("name", "DeepSeek");
            deepSeek.put("displayName", "DeepSeek");
            deepSeek.put("models", List.of(
                DeepSeekClient.MODEL_CHAT,
                DeepSeekClient.MODEL_REASONER
            ));
            providers.add(deepSeek);
        }

        if (hasOpenRouter) {
            Map<String, Object> openRouter = new HashMap<>();
            openRouter.put("name", "OpenRouter");
            openRouter.put("displayName", "OpenRouter (Free Models)");
            openRouter.put("models", List.of(
                OpenRouterClient.MODEL_GPT_OSS,
                OpenRouterClient.MODEL_LFM_2_5
            ));
            providers.add(openRouter);
        }

        ctx.json(Map.of("success", true, "providers", providers));
    }

    /**
     * Возвращает список всех доступных моделей.
     */
    private static void handleGetModels(Context ctx) {
        List<Map<String, Object>> models = new ArrayList<>();

        for (String model : clientManager.getAvailableModels()) {
            Map<String, Object> modelInfo = new HashMap<>();
            modelInfo.put("id", model);
            modelInfo.put("displayName", PricingService.getModelDisplayName(model));
            modelInfo.put("provider", PricingService.getProviderName(model));
            modelInfo.put("pricePerMillion", PricingService.getFormattedCost(model));
            modelInfo.put("isFree", PricingService.isOpenRouterModel(model) || model.endsWith(":free"));
            models.add(modelInfo);
        }

        ctx.json(Map.of("success", true, "models", models));
    }

    /**
     * Возвращает статус режима сравнения.
     */
    private static void handleGetCompareStatus(Context ctx) {
        ctx.json(Map.of(
            "success", true,
            "compareMode", compareMode,
            "selectedModels", compareModels
        ));
    }

    /**
     * Переключает режим сравнения.
     */
    private static void handleToggleCompare(Context ctx) throws Exception {
        Map<String, Boolean> request = ctx.bodyAsClass(Map.class);
        Boolean enabled = request.get("enabled");

        if (enabled != null) {
            compareMode = enabled;
        } else {
            compareMode = !compareMode;
        }

        ctx.json(Map.of(
            "success", true,
            "compareMode", compareMode,
            "message", compareMode ? "Режим сравнения включён" : "Режим сравнения выключен"
        ));
    }

    /**
     * Устанавливает модели для сравнения.
     */
    private static void handleSetCompareModels(Context ctx) throws Exception {
        Map<String, Object> request = ctx.bodyAsClass(Map.class);

        @SuppressWarnings("unchecked")
        List<String> models = (List<String>) request.get("models");

        if (models == null || models.isEmpty()) {
            ctx.status(400).json(Map.of("success", false, "error", "Список моделей не может быть пустым"));
            return;
        }

        // Фильтруем только доступные модели
        List<String> validModels = new ArrayList<>();
        for (String model : models) {
            if (clientManager.hasClient(model)) {
                validModels.add(model);
            }
        }

        if (validModels.isEmpty()) {
            ctx.status(400).json(Map.of("success", false, "error", "Нет доступных моделей из списка"));
            return;
        }

        compareModels = validModels;

        ctx.json(Map.of(
            "success", true,
            "selectedModels", compareModels,
            "message", "Выбрано моделей: " + compareModels.size()
        ));
    }

    private static void handleHistory(Context ctx) {
        ctx.json(Map.of(
            "history", chatHistory,
            "mode", currentMode,
            "modeName", currentMode == 1 ? "Тестировщик" : "Помощник"
        ));
    }

    private static void handleInfo(Context ctx) {
        Map<String, Object> info = new HashMap<>();
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("osName", System.getProperty("os.name"));
        info.put("osVersion", System.getProperty("os.version"));
        info.put("userDir", System.getProperty("user.dir"));
        info.put("fileEncoding", System.getProperty("file.encoding"));
        info.put("userName", System.getProperty("user.name"));

        ctx.json(Map.of("success", true, "info", info));
    }

    private static void handleSystem(Context ctx) {
        String systemMessage = clientManager.getSystemMessage();
        ctx.json(Map.of(
            "success", true,
            "systemPrompt", systemMessage != null ? systemMessage : "",
            "modeDescription", currentMode == 1 ? "Тестировщик" : "Помощник"
        ));
    }

    private static void handleLimited(Context ctx) throws Exception {
        Map<String, String> request = ctx.bodyAsClass(Map.class);
        String message = request.get("message");

        if (message == null || message.isBlank()) {
            ctx.status(400).json(Map.of("success", false, "error", "Сообщение не может быть пустым"));
            return;
        }

        try {
            // Получаем текущий клиент
            AiClient client = clientManager.getCurrentClient();
            String response;

            // Проверяем тип клиента для вызова метода chatLimited
            if (client instanceof DeepSeekClientAdapter) {
                DeepSeekClient deepSeekClient = ((DeepSeekClientAdapter) client).getDelegate();
                response = deepSeekClient.chatLimited(message);
            } else if (client instanceof DeepSeekClient) {
                response = ((DeepSeekClient) client).chatLimited(message);
            } else {
                // Для других клиентов используем обычный метод с ограничениями
                client.setMaxTokens(100);
                client.setMaxTokensEnabled(true);
                response = client.chat(message);
            }

            var metrics = clientManager.getLastMetrics();

            chatHistory.add(new ChatMessage("user", message));
            chatHistory.add(new ChatMessage("assistant", response));

            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("response", response);
            responseMap.put("success", true);
            responseMap.put("limited", true);

            if (metrics != null) {
                responseMap.put("metrics", buildMetricsMap(metrics));
            }

            ctx.json(responseMap);
        } catch (Exception e) {
            e.printStackTrace();
            ctx.status(500).json(Map.of("success", false, "error", "Ошибка: " + e.getMessage()));
        }
    }

    private static void handleGetSettings(Context ctx) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("mode", currentMode);
        settings.put("modeDescription", currentMode == 1 ? "Тестировщик" : "Помощник");
        settings.put("maxTokens", clientManager.getCurrentClient().getMaxTokens());
        settings.put("maxTokensEnabled", clientManager.getCurrentClient().isMaxTokensEnabled());
        settings.put("temperature", clientManager.getCurrentClient().getTemperature());
        settings.put("temperatureEnabled", clientManager.getCurrentClient().isTemperatureEnabled());
        settings.put("systemPrompt", clientManager.getSystemMessage());
        settings.put("model", clientManager.getCurrentModel());
        settings.put("availableModels", clientManager.getAvailableModels());

        ctx.json(Map.of("success", true, "settings", settings));
    }

    private static void handleSetSettings(Context ctx) throws Exception {
        Map<String, Object> request = ctx.bodyAsClass(Map.class);
        AiClient client = clientManager.getCurrentClient();
        String param = (String) request.get("param");

        // Обработка параметра по имени
        if (param != null) {
            switch (param) {
                case "max_tokens":
                    Integer maxTokens = (Integer) request.get("value");
                    if (maxTokens != null && maxTokens > 0) {
                        client.setMaxTokens(maxTokens);
                        ctx.json(Map.of("success", true, "message", "Максимальное количество токенов установлено: " + maxTokens));
                        return;
                    }
                    break;
                    
                case "max_tokens_enabled":
                    Boolean maxTokensEnabled = (Boolean) request.get("value");
                    if (maxTokensEnabled != null) {
                        client.setMaxTokensEnabled(maxTokensEnabled);
                        ctx.json(Map.of("success", true, "message", 
                            maxTokensEnabled ? "Ограничение токенов включено" : "Ограничение токенов выключено"));
                        return;
                    }
                    break;
                    
                case "temperature":
                    Object tempValue = request.get("value");
                    Double temperature = null;
                    if (tempValue instanceof Number) {
                        temperature = ((Number) tempValue).doubleValue();
                    }
                    if (temperature != null && temperature >= 0 && temperature <= 2) {
                        client.setTemperature(temperature);
                        ctx.json(Map.of("success", true, "message", "Temperature установлена: " + temperature));
                        return;
                    }
                    break;
                    
                case "temperature_enabled":
                    Boolean temperatureEnabled = (Boolean) request.get("value");
                    if (temperatureEnabled != null) {
                        client.setTemperatureEnabled(temperatureEnabled);
                        ctx.json(Map.of("success", true, "message", 
                            temperatureEnabled ? "Temperature включена" : "Temperature выключена"));
                        return;
                    }
                    break;
                    
                case "system_prompt":
                    String systemPrompt = (String) request.get("value");
                    if (systemPrompt != null && !systemPrompt.isBlank()) {
                        clientManager.setSystemMessage(systemPrompt);
                        ctx.json(Map.of("success", true, "message", "Системный промпт обновлён"));
                        return;
                    }
                    break;
            }
        }

        // Старый формат для совместимости
        if (request.containsKey("maxTokens")) {
            Integer maxTokens = (Integer) request.get("maxTokens");
            if (maxTokens != null && maxTokens > 0) {
                client.setMaxTokens(maxTokens);
            }
        }

        if (request.containsKey("temperature")) {
            Object tempValue = request.get("temperature");
            Double temperature = null;
            if (tempValue instanceof Number) {
                temperature = ((Number) tempValue).doubleValue();
            }
            if (temperature != null && temperature >= 0 && temperature <= 2) {
                client.setTemperature(temperature);
            }
        }

        ctx.json(Map.of("success", true, "message", "Настройки обновлены"));
    }

    private static void handleGetThinking(Context ctx) {
        ctx.json(Map.of(
            "success", true,
            "thinkingEnabled", clientManager.isThinkingEnabled(),
            "supportsThinking", clientManager.supportsThinking(),
            "currentModel", clientManager.getCurrentModel()
        ));
    }

    private static void handleSetThinking(Context ctx) throws Exception {
        Map<String, Boolean> request = ctx.bodyAsClass(Map.class);
        Boolean enabled = request.get("enabled");

        if (enabled == null) {
            ctx.status(400).json(Map.of("success", false, "error", "Параметр 'enabled' обязателен"));
            return;
        }

        if (!clientManager.supportsThinking()) {
            ctx.status(400).json(Map.of("success", false, "error", "Thinking mode поддерживается только для DeepSeek Reasoner"));
            return;
        }

        clientManager.setThinkingEnabled(enabled);
        ctx.json(Map.of(
            "success", true,
            "thinkingEnabled", enabled,
            "message", enabled ? "Thinking mode включён" : "Thinking mode выключен"
        ));
    }

    // Класс для хранения сообщений чата
    public static class ChatMessage {
        public String role;
        public String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        // Getters для JSON сериализации
        public String getRole() { return role; }
        public String getContent() { return content; }
    }
}
