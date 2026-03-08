package com.example.deepseek.app;

import com.example.deepseek.agent.SummaryAgent;
import com.example.deepseek.client.*;
import com.example.deepseek.context.ContextManager;
import com.example.deepseek.db.*;
import com.example.deepseek.dto.RequestMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.json.JavalinJackson;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.List;

/**
 * Веб-приложение для DeepSeek CLI с интерфейсом в браузере.
 * Использует Javalin - легковесный веб-фреймворк.
 */
public class WebApp {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(WebApp.class);

    private static final int DEFAULT_PORT = 8080;
    private static final String DEEPSEEK_API_KEY_ENV = "DEEPSEEK_API_KEY";
    private static final String OPENROUTER_API_KEY_ENV = "OPENROUTER_API_KEY";

    private static ClientManager clientManager;
    private static ContextManager contextManager;
    private static SummaryAgent summaryAgent;
    private static ObjectMapper objectMapper;
    private static List<ChatMessage> chatHistory = new ArrayList<>();
    private static int currentMode = 2;
    private static SessionService sessionService;

    // Режим сравнения моделей
    private static boolean compareMode = false;
    private static List<String> compareModels = new ArrayList<>();

    public static void main(String[] args) {
        // Инициализация ClientManager
        clientManager = new ClientManager();

        // Инициализация SessionService (загружает последнюю сессию)
        sessionService = new SessionService();

        // Инициализация контекст-менеджера и агента для сжатия
        contextManager = new ContextManager(sessionService.getSessionRepository());
        summaryAgent = new SummaryAgent(clientManager, sessionService);

        // Установка зависимостей
        sessionService.setSummaryAgent(summaryAgent);
        sessionService.setContextScheduler(new com.example.deepseek.context.ContextScheduler(summaryAgent, sessionService.getMessageRepository()));

        // Загрузка API ключей и регистрация клиентов
        String deepSeekApiKey = System.getenv(DEEPSEEK_API_KEY_ENV);
        String openRouterApiKey = System.getenv(OPENROUTER_API_KEY_ENV);

        boolean hasDeepSeek = deepSeekApiKey != null && !deepSeekApiKey.isBlank();
        boolean hasOpenRouter = openRouterApiKey != null && !openRouterApiKey.isBlank();

        if (!hasDeepSeek && !hasOpenRouter) {
            log.error("Ошибка: Не установлена ни одна переменная окружения для API ключей");
            log.error("Установите хотя бы один API ключ:");
            log.error("  set " + DEEPSEEK_API_KEY_ENV + "=your_deepseek_api_key");
            log.error("  или");
            log.error("  set " + OPENROUTER_API_KEY_ENV + "=your_openrouter_api_key");
            System.exit(1);
        }

        // Регистрируем клиентов DeepSeek
        if (hasDeepSeek) {
            log.info("✓ DeepSeek API ключ найден");
            clientManager.registerClient(DeepSeekClient.MODEL_CHAT,
                new DeepSeekClient(deepSeekApiKey, DeepSeekClient.MODEL_CHAT));
            clientManager.registerClient(DeepSeekClient.MODEL_REASONER,
                new DeepSeekClient(deepSeekApiKey, DeepSeekClient.MODEL_REASONER));
        }

        // Регистрируем клиентов OpenRouter
        if (hasOpenRouter) {
            log.info("✓ OpenRouter API ключ найден");
            clientManager.registerClient(OpenRouterClient.MODEL_GPT_OSS,
                new OpenRouterClient(openRouterApiKey, OpenRouterClient.MODEL_GPT_OSS));
            clientManager.registerClient(OpenRouterClient.MODEL_LFM_2_5,
                new OpenRouterClient(openRouterApiKey, OpenRouterClient.MODEL_LFM_2_5));
        } else {
            log.info("⚠ OpenRouter API ключ не найден. Установите " + OPENROUTER_API_KEY_ENV);
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

        // Инициализируем contextManager и summaryAgent для всех зарегистрированных клиентов
        clientManager.initializeContextManager(contextManager, summaryAgent);

        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                log.info("Неверный порт, использую стандартный: " + DEFAULT_PORT);
            }
        }

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/static");
            config.jsonMapper(new JavalinJackson(objectMapper, true));
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

        // Endpoints для сессий
        app.get("/api/sessions", WebApp::handleGetSessions);
        app.post("/api/sessions", WebApp::handleCreateSession);
        app.get("/api/sessions/active", WebApp::handleGetActiveSession);
        app.get("/api/sessions/{id}", WebApp::handleGetSession);
        app.delete("/api/sessions/{id}", WebApp::handleDeleteSession);
        app.get("/api/sessions/{id}/messages", WebApp::handleGetSessionMessages);
        app.post("/api/sessions/{id}/activate", WebApp::handleActivateSession);
        app.get("/api/sessions/{id}/stats", WebApp::handleGetSessionStats);

        // Endpoints для настроек контекста
        app.get("/api/sessions/{id}/context-settings", WebApp::handleGetContextSettings);
        app.post("/api/sessions/{id}/context-settings", WebApp::handleSetContextSettings);
        app.post("/api/sessions/{id}/keep-messages", WebApp::handleUpdateKeepMessagesCount);
        app.post("/api/sessions/{id}/summary-interval", WebApp::handleUpdateSummaryInterval);
        app.post("/api/sessions/{id}/summary-enabled", WebApp::handleUpdateSummaryEnabled);

        // Endpoints для настройки компрессии
        app.get("/api/compression-enabled", WebApp::handleGetCompressionEnabled);
        app.post("/api/compression-enabled", WebApp::handleSetCompressionEnabled);

        // Запускаем сервер
        app.start(port);
        
        // Не восстанавливаем сессию здесь - фронтенд сам загрузит при инициализации
        
        log.info("Server started on port {}", port);
        log.info("╔══════════════════════════════════════════════════════════╗");
        log.info("║           AI Chat Interface - Запущен!                   ║");
        log.info("╠══════════════════════════════════════════════════════════╣");
        log.info("║  Откройте в браузере: http://localhost:{}              ║", port);
        log.info("║  Нажмите Ctrl+C для остановки сервера                    ║");
        log.info("╚══════════════════════════════════════════════════════════╝");

        // Открываем браузер
        openBrowser("http://localhost:" + port);
    }

    private static void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;

            log.info("[APP] openBrowser: OS=" + os + ", url=" + url);

            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", "start", "chrome", url);
                try {
                    pb.start();
                    log.info("[APP] openBrowser: Chrome запущен (Windows)");
                } catch (java.io.IOException e) {
                    pb = new ProcessBuilder("cmd", "/c", "start", url);
                    pb.start();
                    log.info("[APP] openBrowser: браузер по умолчанию запущен (Windows)");
                }
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", "-a", "Google Chrome", url);
                try {
                    pb.start();
                    log.info("[APP] openBrowser: Chrome запущен (Mac)");
                } catch (java.io.IOException e) {
                    pb = new ProcessBuilder("open", url);
                    pb.start();
                    log.info("[APP] openBrowser: браузер по умолчанию запущен (Mac)");
                }
            } else {
                pb = new ProcessBuilder("xdg-open", url);
                pb.start();
                log.info("[APP] openBrowser: браузер запущен (Linux)");
            }
        } catch (Exception e) {
            log.info("[APP] openBrowser: ошибка - " + e.getMessage());
            log.info("[APP] openBrowser: откройте вручную: " + url);
        }
    }

    // ==================== API HANDLERS ====================

    private static void handleChat(Context ctx) throws Exception {
        Map<String, Object> request = ctx.bodyAsClass(Map.class);
        String message = null;

        if (request.containsKey("messages")) {
            List<Map<String, String>> messages = (List<Map<String, String>>) request.get("messages");
            if (messages != null && !messages.isEmpty()) {
                long sessionId = sessionService.getCurrentSessionId();
                log.info("Chat request: session_id={}, messages_count={}", sessionId, messages.size());

                for (Map<String, String> msg : messages) {
                    String role = msg.get("role");
                    String content = msg.get("content");
                    if (role != null && content != null) {
                        sessionService.saveMessage(role, content, 0, 0, 0, 0, 0, 0.0);
                        if ("user".equals(role) && message == null) {
                            message = content;
                        }
                    }
                }
            }
        } else {
            message = (String) request.get("message");
        }

        if (message == null || message.isBlank()) {
            ctx.status(400).json(Map.of("success", false, "error", "Сообщение не может быть пустым"));
            return;
        }

        try {
            long startTime = System.currentTimeMillis();
            long sessionId = sessionService.getCurrentSessionId();

            sessionService.saveMessage("user", message, 0, 0, 0, 0, 0, 0.0);

            String response = clientManager.chat(sessionId, message);
            log.info("Chat response: session_id={}, response_length={}", sessionId, response != null ? response.length() : 0);
            long latency = System.currentTimeMillis() - startTime;
            var metrics = clientManager.getLastMetrics();

            chatHistory.add(new ChatMessage("user", message));
            chatHistory.add(new ChatMessage("assistant", response,
                metrics != null ? metrics.getInputTokens() : 0,
                metrics != null ? metrics.getOutputTokens() : 0,
                (int) latency,
                metrics != null ? metrics.getCostUsd() : 0.0));

            sessionService.saveMessageAsync("assistant", response,
                metrics != null ? metrics.getInputTokens() : 0,
                metrics != null ? metrics.getOutputTokens() : 0,
                metrics != null ? metrics.getTotalTokens() : 0,
                metrics != null ? metrics.getCachedTokens() : 0,
                (int) latency,
                metrics != null ? metrics.getCostUsd() : 0.0);

            sessionService.generateTitleFromFirstMessage();

            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("response", response);
            responseMap.put("success", true);

            if (metrics != null) {
                responseMap.put("metrics", buildMetricsMap(metrics));
            }

            log.info("Chat response: session_id={}, status=success, latency_ms={}, input_tokens={}, output_tokens={}",
                sessionId, latency, metrics != null ? metrics.getInputTokens() : 0, metrics != null ? metrics.getOutputTokens() : 0);

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
        log.info("Compare chat: start");
        
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
            // Сохраняем сообщение пользователя в БД ПЕРЕД отправкой запросов
            sessionService.saveMessage("user", message, 0, 0, 0, 0, 0, 0.0);

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
                    var metrics = mr.getMetrics();
                    chatHistory.add(new ChatMessage("assistant", "[" + mr.getModelDisplayName() + "] " + mr.getResponse(),
                        metrics != null ? metrics.getInputTokens() : 0,
                        metrics != null ? metrics.getOutputTokens() : 0,
                        (int) mr.getLatencyMs(),
                        metrics != null ? metrics.getCostUsd() : 0.0));
                    // Сохраняем ответ ассистента в БД
                    sessionService.saveMessageAsync("assistant", "[" + mr.getModelDisplayName() + "] " + mr.getResponse(),
                        metrics != null ? metrics.getInputTokens() : 0,
                        metrics != null ? metrics.getOutputTokens() : 0,
                        metrics != null ? metrics.getTotalTokens() : 0,
                        metrics != null ? metrics.getCachedTokens() : 0,
                        (int) mr.getLatencyMs(),
                        metrics != null ? metrics.getCostUsd() : 0.0);
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
        metricsMap.put("cachedTokens", metrics.getCachedTokens());
        metricsMap.put("latencyMs", metrics.getLatencyMs());
        metricsMap.put("costUsd", metrics.getCostUsd());
        metricsMap.put("formattedCost", metrics.getFormattedCost());
        metricsMap.put("formattedLatency", metrics.getFormattedLatency());
        metricsMap.put("model", metrics.getModel());
        return metricsMap;
    }

    private static void handleClear(Context ctx) {
        long oldSessionId = sessionService.getCurrentSessionId();
        log.info("Clear history: old_session_id={}", oldSessionId);
        
        chatHistory.clear();
        clientManager.clearAllHistory();

        // Создаем новую сессию при очистке
        long newSessionId = sessionService.createSession(
            "Новая сессия",
            clientManager.getCurrentModel(),
            clientManager.getSystemMessage(),
            currentMode
        );

        log.info("Clear history: created new session_id={}", newSessionId);
        ctx.json(Map.of("success", true, "message", "История очищена, создана новая сессия"));
    }

    private static void handleGetMode(Context ctx) {
        log.info("Get mode: current_mode={}", currentMode);
        ctx.json(Map.of(
            "mode", currentMode,
            "modeName", currentMode == 1 ? "Тестировщик" : "Помощник"
        ));
    }

    private static void handleSetMode(Context ctx) throws Exception {
        Map<String, Integer> request = ctx.bodyAsClass(Map.class);
        Integer mode = request.get("mode");

        log.info("Set mode: old_mode={}, new_mode={}", currentMode, mode);

        if (mode == null || (mode != 1 && mode != 2)) {
            ctx.status(400).json(Map.of("success", false, "error", "Режим должен быть 1 (Tester) или 2 (Helper)"));
            return;
        }

        currentMode = mode;
        clientManager.setMode(mode);
        clientManager.clearAllHistory();
        chatHistory.clear();

        // Создаем новую сессию при смене режима
        long newSessionId = sessionService.createSession(
            "Новая сессия",
            clientManager.getCurrentModel(),
            clientManager.getSystemMessage(),
            currentMode
        );

        ctx.json(Map.of(
            "success", true,
            "mode", currentMode,
            "modeName", currentMode == 1 ? "Тестировщик" : "Помощник",
            "message", "Режим изменён, создана новая сессия"
        ));
    }

    private static void handleGetModel(Context ctx) {
        String model = clientManager.getCurrentModel();
        log.info("Get model: current_model={}", model);
        ctx.json(Map.of(
            "model", model,
            "modelName", PricingService.getModelDisplayName(model),
            "provider", PricingService.getProviderName(model)
        ));
    }

    private static void handleSetModel(Context ctx) throws Exception {
        Map<String, String> request = ctx.bodyAsClass(Map.class);
        String newModel = request.get("model");
        String oldModel = clientManager.getCurrentModel();

        log.info("Set model: old_model={}, new_model={}", oldModel, newModel);

        if (newModel == null || !clientManager.hasClient(newModel)) {
            String errorMsg = "Модель не найдена или недоступна: " + newModel;
            if (newModel != null && newModel.contains("/")) {
                errorMsg += ". Установите переменную окружения OPENROUTER_API_KEY";
            }
            ctx.status(400).json(Map.of("success", false, "error", errorMsg));
            return;
        }

        // Отключаем режим сравнения при смене модели
        compareMode = false;

        clientManager.setCurrentModel(newModel);
        sessionService.updateSessionModel(newModel);

        log.info("Set model: success, new_model={}", newModel);
        ctx.json(Map.of(
            "success", true,
            "model", newModel,
            "modelName", PricingService.getModelDisplayName(newModel),
            "provider", PricingService.getProviderName(newModel),
            "compareMode", compareMode,
            "message", "Модель изменена на " + PricingService.getModelDisplayName(newModel)
        ));
    }

    /**
     * Возвращает список доступных провайдеров.
     */
    private static void handleGetProviders(Context ctx) {
        log.info("Get providers");
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
        log.info("Get models");
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
        log.info("Get compare status: compare_mode={}", compareMode);
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
        
        log.info("Toggle compare: current_mode={}, new_enabled={}", compareMode, enabled != null ? enabled : !compareMode);

        if (enabled != null) {
            compareMode = enabled;
        } else {
            compareMode = !compareMode;
        }

        log.info("Toggle compare: result={}", compareMode);
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
        
        log.info("Set compare models: requested_count={}", models != null ? models.size() : 0);

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
        // Загружаем сообщения из БД для текущей сессии
        List<MessageDto> sessionMessages = sessionService.getSessionMessages(sessionService.getCurrentSessionId());
        
        List<ChatMessage> history = new ArrayList<>();
        for (var msg : sessionMessages) {
            history.add(new ChatMessage(msg.role(), msg.content(), 
                msg.inputTokens(), msg.outputTokens(), msg.latency(), msg.cost()));
        }
        
        log.info("Get history: session_id={}, message_count={}", sessionService.getCurrentSessionId(), history.size());
        ctx.json(Map.of(
            "history", history,
            "mode", currentMode,
            "modeName", currentMode == 1 ? "Тестировщик" : "Помощник"
        ));
    }

    private static void handleInfo(Context ctx) {
        log.info("Get info");
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
        log.info("Get system prompt");
        String systemMessage = clientManager.getSystemMessage();
        ctx.json(Map.of(
            "success", true,
            "systemPrompt", systemMessage != null ? systemMessage : "",
            "modeDescription", currentMode == 1 ? "Тестировщик" : "Помощник"
        ));
    }

    private static void handleLimited(Context ctx) throws Exception {
        log.info("Limited chat: start");
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
            if (client instanceof DeepSeekClient) {
                response = ((DeepSeekClient) client).chatLimited(message);
            } else {
                // Для других клиентов используем обычный метод с ограничениями
                client.setMaxTokens(100);
                client.setMaxTokensEnabled(true);
                response = client.chat(message);
            }

            var metrics = clientManager.getLastMetrics();

            chatHistory.add(new ChatMessage("user", message));
            chatHistory.add(new ChatMessage("assistant", response,
                metrics != null ? metrics.getInputTokens() : 0,
                metrics != null ? metrics.getOutputTokens() : 0,
                0,
                metrics != null ? metrics.getCostUsd() : 0.0));

            // Сохраняем сообщения в БД
            sessionService.saveMessage("user", message, 0, 0, 0, 0, 0, 0.0);
            sessionService.saveMessageAsync("assistant", response,
                metrics != null ? metrics.getInputTokens() : 0,
                metrics != null ? metrics.getOutputTokens() : 0,
                metrics != null ? metrics.getTotalTokens() : 0,
                metrics != null ? metrics.getCachedTokens() : 0,
                0,
                metrics != null ? metrics.getCostUsd() : 0.0);

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
        log.info("Get settings");
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

        try {
            long sessionId = sessionService.getCurrentSessionId();
            var sessionSettings = sessionService.getContextSettings(sessionId);
            settings.put("compressionEnabled", sessionSettings.summaryEnabled());
        } catch (Exception e) {
            log.warn("Error getting compression settings: {}", e.getMessage());
            settings.put("compressionEnabled", true);
        }

        ctx.json(Map.of("success", true, "settings", settings));
    }

    private static void handleSetSettings(Context ctx) throws Exception {
        Map<String, Object> request = ctx.bodyAsClass(Map.class);
        AiClient client = clientManager.getCurrentClient();
        String param = (String) request.get("param");

        log.info("Set settings: param={}", param);

        // Обработка параметра по имени
        if (param != null) {
            log.info("Set settings: param={}, value={}", param, request.get("value"));
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
        log.info("Get thinking");
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

        log.info("Set thinking: requested_enabled={}", enabled);

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

    private static void handleGetSessions(Context ctx) {
        log.info("Get sessions");
        List<SessionDto> sessions = sessionService.getAllSessions();
        ctx.json(Map.of("success", true, "sessions", sessions));
    }

    private static void handleCreateSession(Context ctx) throws Exception {
        Map<String, String> request = ctx.bodyAsClass(Map.class);
        String title = request.get("title");

        log.info("Create session: title={}", title);
        
        long sessionId = sessionService.createSession(
            title != null ? title : "Новая сессия",
            clientManager.getCurrentModel(),
            clientManager.getSystemMessage(),
            currentMode
        );

        clientManager.clearAllHistory();
        chatHistory.clear();

        SessionDto session = sessionService.getSession(sessionId).orElseThrow();
        log.info("Create session: success, session_id={}", sessionId);
        ctx.json(Map.of("success", true, "session", session));
    }

    private static void handleGetSession(Context ctx) {
        long id = Long.parseLong(ctx.pathParam("id"));
        log.info("Get session: id={}", id);
        var session = sessionService.getSession(id);

        if (session.isPresent()) {
            ctx.json(Map.of("success", true, "session", session.get()));
        } else {
            ctx.status(404).json(Map.of("success", false, "error", "Сессия не найдена"));
        }
    }

    private static void handleDeleteSession(Context ctx) {
        long id = Long.parseLong(ctx.pathParam("id"));
        long currentId = sessionService.getCurrentSessionId();
        log.info("Delete session: id={}, current_session_id={}", id, currentId);
        
        sessionService.deleteSession(id);
        
        // Если удалили активную сессию - переключаемся на другую существующую или создаём новую
        if (id == currentId) {
            var sessions = sessionService.getAllSessions();
            if (!sessions.isEmpty()) {
                // Активируем первую (самую свежую) сессию
                SessionDto firstSession = sessions.get(0);
                sessionService.setActiveSession(firstSession.id());
                sessionService.restoreSessionToClient(clientManager, summaryAgent);
                
                chatHistory.clear();
                for (var msg : sessionService.getSessionMessages(firstSession.id())) {
                    chatHistory.add(new ChatMessage(msg.role(), msg.content(), 
                        msg.inputTokens(), msg.outputTokens(), msg.latency(), msg.cost()));
                }
                log.info("Delete session: switched to session_id={}", firstSession.id());
            } else {
                // Нет других сессий - создаём новую
                long newSessionId = sessionService.createSession(
                    "Новая сессия",
                    clientManager.getCurrentModel(),
                    clientManager.getSystemMessage(),
                    currentMode
                );
                clientManager.clearAllHistory();
                chatHistory.clear();
                log.info("Delete session: created new session_id={}", newSessionId);
            }
        }
        
        ctx.json(Map.of("success", true, "message", "Сессия удалена"));
    }

    private static void handleGetSessionMessages(Context ctx) {
        long id = Long.parseLong(ctx.pathParam("id"));
        log.info("Get session messages: session_id={}", id);
        List<MessageDto> messages = sessionService.getSessionMessages(id);
        ctx.json(Map.of("success", true, "messages", messages));
    }

    private static void handleActivateSession(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        log.info("Activate session: id={}", id);
        
        var sessionOpt = sessionService.getSession(id);

        if (sessionOpt.isEmpty()) {
            ctx.status(404).json(Map.of("success", false, "error", "Сессия не найдена"));
            return;
        }

        SessionDto session = sessionOpt.get();
        sessionService.setActiveSession(id);

        clientManager.clearAllHistory();
        clientManager.setCurrentModel(session.model() != null ? session.model() : clientManager.getCurrentModel());
        clientManager.setMode(session.mode());
        if (session.systemMessage() != null) {
            clientManager.setSystemMessage(session.systemMessage());
        }

        sessionService.restoreSessionToClient(clientManager, summaryAgent);

        chatHistory.clear();
        for (var msg : sessionService.getSessionMessages(id)) {
            chatHistory.add(new ChatMessage(msg.role(), msg.content()));
        }

        log.info("Activate session: success, session_id={}, title={}, message_count={}", 
                session.id(), session.title(), chatHistory.size());
        ctx.json(Map.of("success", true, "session", session, "message", "Сессия активирована"));
    }

    private static void handleGetActiveSession(Context ctx) {
        log.info("Get active session");
        var session = sessionService.getActiveSession();
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("session", session.orElse(null));
        ctx.json(response);
    }

    private static void handleGetSessionStats(Context ctx) {
        long id = Long.parseLong(ctx.pathParam("id"));
        log.info("Get session stats: session_id={}", id);
        
        var stats = sessionService.getSessionStats(id);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("stats", Map.of(
            "totalTokens", stats.totalTokens(),
            "totalCost", stats.totalCost(),
            "requestCount", stats.requestCount()
        ));
        ctx.json(response);
    }

    private static void handleGetContextSettings(Context ctx) {
        long id = Long.parseLong(ctx.pathParam("id"));
        log.info("Get context settings: session_id={}", id);

        try {
            var settings = sessionService.getContextSettings(id);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("settings", Map.of(
                "keepMessagesCount", settings.keepMessagesCount(),
                "summaryInterval", settings.summaryInterval(),
                "summaryBufferSize", settings.summaryBufferSize()
            ));
            ctx.json(response);
        } catch (Exception e) {
            log.error("Error getting context settings: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleSetContextSettings(Context ctx) {
        long id = Long.parseLong(ctx.pathParam("id"));
        Map<String, Object> request = ctx.bodyAsClass(Map.class);
        Integer keepMessagesCount = (Integer) request.get("keepMessagesCount");
        Integer summaryInterval = (Integer) request.get("summaryInterval");

        log.info("Set context settings: session_id={}, keepMessagesCount={}, summaryInterval={}",
            id, keepMessagesCount, summaryInterval);

        if (keepMessagesCount == null || keepMessagesCount < 1 || keepMessagesCount > 100) {
            ctx.status(400).json(Map.of("success", false, "error", "keepMessagesCount должен быть от 1 до 100"));
            return;
        }

        if (summaryInterval == null || summaryInterval < 1 || summaryInterval > 100) {
            ctx.status(400).json(Map.of("success", false, "error", "summaryInterval должен быть от 1 до 100"));
            return;
        }

        try {
            sessionService.updateContextSettings(id, keepMessagesCount, summaryInterval);
            ctx.json(Map.of("success", true, "message", "Настройки контекста обновлены"));
        } catch (Exception e) {
            log.error("Error setting context settings: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleUpdateKeepMessagesCount(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        Map<String, Object> request = ctx.bodyAsClass(Map.class);
        int count = ((Number) request.get("count")).intValue();
        sessionService.updateKeepMessagesCount(id, count);
        ctx.result(objectMapper.writeValueAsString(Map.of("status", "success")));
    }

    private static void handleUpdateSummaryInterval(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        Map<String, Object> request = ctx.bodyAsClass(Map.class);
        int interval = ((Number) request.get("interval")).intValue();
        sessionService.updateSummaryInterval(id, interval);
        ctx.result(objectMapper.writeValueAsString(Map.of("status", "success")));
    }

    private static void handleUpdateSummaryEnabled(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        Map<String, Object> request = ctx.bodyAsClass(Map.class);
        int enabled = ((Number) request.get("enabled")).intValue();
        sessionService.updateSummaryEnabled(id, enabled == 1);
        ctx.result(objectMapper.writeValueAsString(Map.of("status", "success")));
    }

    private static void handleGetCompressionEnabled(Context ctx) {
        try {
            long sessionId = sessionService.getCurrentSessionId();
            var settings = sessionService.getContextSettings(sessionId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("enabled", settings.summaryEnabled());
            ctx.json(response);
        } catch (Exception e) {
            log.error("Error getting compression enabled: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleSetCompressionEnabled(Context ctx) {
        Map<String, Object> request = ctx.bodyAsClass(Map.class);
        Boolean enabled = (Boolean) request.get("enabled");

        if (enabled == null) {
            ctx.status(400).json(Map.of("success", false, "error", "Параметр 'enabled' обязателен"));
            return;
        }

        try {
            long sessionId = sessionService.getCurrentSessionId();
            sessionService.updateSummaryEnabled(sessionId, enabled);
            ctx.json(Map.of("success", true, "message",
                enabled ? "Компрессия контекста включена" : "Компрессия контекста выключена"));
        } catch (Exception e) {
            log.error("Error setting compression enabled: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // Класс для хранения сообщений чата
    public static class ChatMessage {
        public String role;
        public String content;
        public Integer inputTokens;
        public Integer outputTokens;
        public Integer latency;
        public Double cost;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public ChatMessage(String role, String content, int inputTokens, int outputTokens, int latency, double cost) {
            this.role = role;
            this.content = content;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.latency = latency;
            this.cost = cost;
        }

        // Getters для JSON сериализации
        public String getRole() { return role; }
        public String getContent() { return content; }
        public Integer getInputTokens() { return inputTokens; }
        public Integer getOutputTokens() { return outputTokens; }
        public Integer getLatency() { return latency; }
        public Double getCost() { return cost; }
    }
}
