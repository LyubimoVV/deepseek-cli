package com.example.deepseek.app;

import com.example.deepseek.agent.FactsExtractionAgent;
import com.example.deepseek.agent.SummaryAgent;
import com.example.deepseek.client.*;
import com.example.deepseek.context.ContextManager;
import com.example.deepseek.context.ContextScheduler;
import com.example.deepseek.context.ContextStrategy;
import com.example.deepseek.context.ContextStrategyFactory;
import com.example.deepseek.context.strategies.BranchingContextStrategyHandler;
import com.example.deepseek.context.strategies.CompressionContextStrategyHandler;
import com.example.deepseek.context.strategies.NoneContextStrategyHandler;
import com.example.deepseek.context.strategies.SlidingWindowContextStrategyHandler;
import com.example.deepseek.context.strategies.StickyFactsContextStrategyHandler;
import com.example.deepseek.db.*;
import com.example.deepseek.dto.RequestMetrics;
import com.example.deepseek.memory.MemoryService;
import com.example.deepseek.memory.agent.MemoryExtractionAgent;
import com.example.deepseek.memory.dto.MemorySuggestion;
import com.example.deepseek.memory.repository.ProfileRepository;
import com.example.deepseek.memory.repository.impl.ProfileRepositoryImpl;
import com.example.deepseek.memory.repository.impl.WorkingMemoryRepositoryImpl;
import com.example.deepseek.memory.repository.impl.LongTermMemoryRepositoryImpl;
import com.example.deepseek.dto.MemoryRequest;
import com.example.deepseek.dto.ProfileRequest;
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
    private static ContextStrategyFactory strategyFactory;
    private static ObjectMapper objectMapper;
    private static List<ChatMessage> chatHistory = new ArrayList<>();
    private static int currentMode = 2;
    private static SessionService sessionService;
    private static GlobalSummaryRepository globalSummaryRepository;
    private static ProfileRepository profileRepository;
    private static MemoryService memoryService;
    private static MemoryExtractionAgent memoryExtractionAgent;

    public static void main(String[] args) {
        // Инициализация ClientManager
        clientManager = new ClientManager();

        // Инициализация SessionService (загружает последнюю сессию)
        sessionService = new SessionService();

        // Инициализация контекст-менеджера и агента для сжатия
        contextManager = new ContextManager(sessionService.getSessionRepository());
        summaryAgent = new SummaryAgent(clientManager, sessionService);
        globalSummaryRepository = new GlobalSummaryRepository();

        // Facts management
        FactsRepository factsRepository = new FactsRepository();
        FactsExtractionAgent factsExtractionAgent = new FactsExtractionAgent(clientManager, factsRepository);

        // Branch management
        BranchRepository branchRepository = new BranchRepository();
        sessionService.setBranchRepository(branchRepository);

        // Установка зависимостей
        sessionService.setSummaryAgent(summaryAgent);
        sessionService.setFactsRepository(factsRepository);
        sessionService.setFactsExtractionAgent(factsExtractionAgent);
        ContextScheduler contextScheduler = new ContextScheduler(summaryAgent, sessionService.getMessageRepository());
        sessionService.setContextScheduler(contextScheduler);

        // Профили и память
        profileRepository = new ProfileRepositoryImpl();
        WorkingMemoryRepositoryImpl workingMemoryRepo = new WorkingMemoryRepositoryImpl();
        LongTermMemoryRepositoryImpl longTermMemoryRepo = new LongTermMemoryRepositoryImpl();
        memoryService = new MemoryService(workingMemoryRepo, longTermMemoryRepo, sessionService.getSessionRepository());
        memoryExtractionAgent = new MemoryExtractionAgent(clientManager);
        sessionService.setMemoryExtractionAgent(memoryExtractionAgent);

        // Создание стратегий управления контекстом
        NoneContextStrategyHandler noneHandler = new NoneContextStrategyHandler(sessionService.getMessageRepository());
        CompressionContextStrategyHandler compressionHandler = new CompressionContextStrategyHandler(
            contextScheduler, summaryAgent, sessionService.getMessageRepository(),
            globalSummaryRepository, sessionService.getSessionRepository()
        );
        SlidingWindowContextStrategyHandler slidingHandler = new SlidingWindowContextStrategyHandler(
            sessionService.getMessageRepository(), sessionService.getSessionRepository()
        );
        StickyFactsContextStrategyHandler stickyFactsHandler = new StickyFactsContextStrategyHandler(
            sessionService.getMessageRepository(), sessionService.getSessionRepository(), factsRepository
        );
        BranchingContextStrategyHandler branchingHandler = new BranchingContextStrategyHandler(
            sessionService.getMessageRepository(), branchRepository
        );

        // Фабрика стратегий
        strategyFactory = new ContextStrategyFactory(noneHandler, compressionHandler, slidingHandler, stickyFactsHandler, branchingHandler);
        sessionService.setStrategyFactory(strategyFactory);


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
            DeepSeekClient chatClient = new DeepSeekClient(
                deepSeekApiKey, DeepSeekClient.MODEL_CHAT,
                null, null, strategyFactory, sessionService.getSessionRepository()
            );
            DeepSeekClient reasonerClient = new DeepSeekClient(
                deepSeekApiKey, DeepSeekClient.MODEL_REASONER,
                null, null, strategyFactory, sessionService.getSessionRepository()
            );
            clientManager.registerClient(DeepSeekClient.MODEL_CHAT, chatClient);
            clientManager.registerClient(DeepSeekClient.MODEL_REASONER, reasonerClient);
        }

        // Регистрируем клиентов OpenRouter
        if (hasOpenRouter) {
            log.info("✓ OpenRouter API ключ найден");
            OpenRouterClient gptOssClient = new OpenRouterClient(
                openRouterApiKey, OpenRouterClient.MODEL_GPT_OSS,
                null, null, strategyFactory, sessionService.getSessionRepository()
            );
            OpenRouterClient lfmClient = new OpenRouterClient(
                openRouterApiKey, OpenRouterClient.MODEL_LFM_2_5,
                null, null, strategyFactory, sessionService.getSessionRepository()
            );
            clientManager.registerClient(OpenRouterClient.MODEL_GPT_OSS, gptOssClient);
            clientManager.registerClient(OpenRouterClient.MODEL_LFM_2_5, lfmClient);
        } else {
            log.info("⚠ OpenRouter API ключ не найден. Установите " + OPENROUTER_API_KEY_ENV);
        }

        // Устанавливаем модель по умолчанию
        String defaultModel = hasDeepSeek ? DeepSeekClient.MODEL_REASONER : OpenRouterClient.MODEL_GPT_OSS;
        clientManager.setCurrentModel(defaultModel);

        // Инициализируем contextManager и summaryAgent для всех зарегистрированных клиентов
        clientManager.initializeContextManager(contextManager, summaryAgent);

        // Инициализируем memoryService для всех клиентов
        clientManager.setMemoryService(memoryService);

        // Загружаем последнюю активную сессию
        sessionService.loadLastSession();

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

        // Новые endpoints для провайдеров и моделей
        app.get("/api/providers", WebApp::handleGetProviders);
        app.get("/api/models", WebApp::handleGetModels);

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

        // Endpoints для стратегий управления контекстом
        app.get("/api/strategies", WebApp::handleGetStrategies);
        app.get("/api/sessions/{id}/context-strategy", WebApp::handleGetContextStrategy);
        app.post("/api/sessions/{id}/context-strategy", WebApp::handleSetContextStrategy);

        // Endpoints для Branching Strategy
        app.get("/api/sessions/{id}/branches", WebApp::handleGetBranches);
        app.post("/api/sessions/{id}/branches", WebApp::handleCreateBranch);
        app.post("/api/sessions/{id}/branches/{branchId}/switch", WebApp::handleSwitchBranch);
        app.delete("/api/sessions/{id}/branches/{branchId}", WebApp::handleDeleteBranch);
        app.get("/api/sessions/{id}/branches/{branchId}/stats", WebApp::handleGetBranchStats);

        // Endpoints для Compression Strategy
        app.get("/api/sessions/{id}/compression-settings", WebApp::handleGetCompressionSettings);
        app.post("/api/sessions/{id}/compression-settings", WebApp::handleSetCompressionSettings);

        // Endpoints для Sliding Window Strategy
        app.get("/api/sessions/{id}/sliding-window-settings", WebApp::handleGetSlidingWindowSettings);
        app.post("/api/sessions/{id}/sliding-window-settings", WebApp::handleSetSlidingWindowSettings);

        // Endpoints для Sticky Facts Strategy
        app.get("/api/sessions/{id}/sticky-facts-settings", WebApp::handleGetStickyFactsSettings);
        app.post("/api/sessions/{id}/sticky-facts-settings", WebApp::handleSetStickyFactsSettings);

        // Endpoints для Facts
        app.get("/api/sessions/{id}/facts", WebApp::handleGetFacts);
        app.post("/api/sessions/{id}/facts", WebApp::handleSaveFact);
        app.put("/api/sessions/{id}/facts/{factId}", WebApp::handleUpdateFact);
        app.delete("/api/sessions/{id}/facts/{factId}", WebApp::handleDeleteFact);
        app.post("/api/sessions/{id}/facts/extract", WebApp::handleExtractFacts);

        // ==================== PROFILES API ====================
        app.get("/api/profiles", WebApp::handleGetProfiles);
        app.post("/api/profiles", WebApp::handleCreateProfile);
        app.get("/api/profiles/{id}", WebApp::handleGetProfile);
        app.put("/api/profiles/{id}", WebApp::handleUpdateProfile);
        app.delete("/api/profiles/{id}", WebApp::handleDeleteProfile);
        app.get("/api/profiles/default", WebApp::handleGetDefaultProfile);
        app.post("/api/sessions/{id}/set-profile/{profileId}", WebApp::handleSetSessionProfile);

        // ==================== MEMORY API ====================
        app.get("/api/sessions/{id}/memory/working", WebApp::handleGetWorkingMemory);
        app.post("/api/sessions/{id}/memory/working", WebApp::handleSaveWorkingMemory);
        app.put("/api/sessions/{id}/memory/working/{key}", WebApp::handleUpdateWorkingMemory);
        app.delete("/api/sessions/{id}/memory/working/{key}", WebApp::handleDeleteWorkingMemory);

        app.get("/api/profiles/{id}/memory/longterm", WebApp::handleGetLongTermMemory);
        app.post("/api/profiles/{id}/memory/longterm", WebApp::handleSaveLongTermMemory);
        app.put("/api/profiles/{id}/memory/longterm/{key}", WebApp::handleUpdateLongTermMemory);
        app.delete("/api/profiles/{id}/memory/longterm/{key}", WebApp::handleDeleteLongTermMemory);

        app.post("/api/sessions/{id}/memory/suggest", WebApp::handleSuggestMemory);
        app.get("/api/sessions/{id}/memory/suggestions", WebApp::handleGetMemorySuggestions);
        app.post("/api/memory/analyze", WebApp::handleAnalyzeText);
        app.post("/api/sessions/{id}/memory/suggestions/viewed", WebApp::handleMarkSuggestionsViewed);

        // Запускаем сервер
        app.start(port);

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
                        long messageId = sessionService.saveMessage(role, content, 0, 0, 0, 0, 0, 0.0);
                        if ("user".equals(role)) {
                            sessionService.onMessageSaved(sessionService.getCurrentSessionId(), role, content);
                        }
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

            long userMessageId = sessionService.saveMessage("user", message, 0, 0, 0, 0, 0, 0.0);
            sessionService.onMessageSaved(sessionId, "user", message);

            String systemMessage = sessionService.getSystemMessage(sessionId);
            String response = clientManager.chat(sessionId, message, systemMessage);
            log.info("Chat response: session_id={}, response_length={}", sessionId, response != null ? response.length() : 0);
            long latency = System.currentTimeMillis() - startTime;
            var metrics = clientManager.getLastMetrics();

            chatHistory.add(new ChatMessage("user", message));
            chatHistory.add(new ChatMessage("assistant", response,
                metrics != null ? metrics.getInputTokens() : 0,
                metrics != null ? metrics.getOutputTokens() : 0,
                (int) latency,
                metrics != null ? metrics.getCostUsd() : 0.0));

            long assistantMessageId = sessionService.saveMessage("assistant", response,
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
            responseMap.put("lastMessageId", assistantMessageId);

            if (metrics != null) {
                responseMap.put("metrics", buildMetricsMap(metrics));
            }

            log.info("Chat response: session_id={}, status=success, latency_ms={}, input_tokens={}, output_tokens={}, last_message_id={}",
                sessionId, latency, metrics != null ? metrics.getInputTokens() : 0, metrics != null ? metrics.getOutputTokens() : 0, assistantMessageId);

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

    private static long getProfileIdForSession(long sessionId) {
        if (sessionId <= 0) {
            return 1L;
        }
        try {
            return sessionService.getSessionRepository().getProfileId(sessionId);
        } catch (Exception e) {
            log.warn("Failed to get profileId for session {}: {}", sessionId, e.getMessage());
            return 1L;
        }
    }

    private static void handleClear(Context ctx) {
        long oldSessionId = sessionService.getCurrentSessionId();
        log.info("Clear history: old_session_id={}", oldSessionId);

        long profileId = getProfileIdForSession(oldSessionId);

        chatHistory.clear();
        clientManager.clearAllHistory();

        long newSessionId = sessionService.createSession(
            "Новая сессия",
            clientManager.getCurrentModel(),
            clientManager.getSystemMessage(),
            currentMode,
            profileId
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

        long oldSessionId = sessionService.getCurrentSessionId();
        long profileId = getProfileIdForSession(oldSessionId);

        long newSessionId = sessionService.createSession(
            "Новая сессия",
            clientManager.getCurrentModel(),
            clientManager.getSystemMessage(),
            currentMode,
            profileId
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

        clientManager.setCurrentModel(newModel);
        sessionService.updateSessionModel(newModel);

        log.info("Set model: success, new_model={}", newModel);
        ctx.json(Map.of(
            "success", true,
            "model", newModel,
            "modelName", PricingService.getModelDisplayName(newModel),
            "provider", PricingService.getProviderName(newModel),
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

    private static void handleHistory(Context ctx) {
        // Загружаем сообщения из БД для текущей сессии
        List<MessageDto> sessionMessages = sessionService.getSessionMessages(sessionService.getCurrentSessionId());

        List<ChatMessage> history = new ArrayList<>();
        for (var msg : sessionMessages) {
            history.add(new ChatMessage(msg.role(), msg.content(),
                msg.inputTokens(), msg.outputTokens(), msg.latency(), msg.cost(), msg.id()));
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

        long oldSessionId = sessionService.getCurrentSessionId();
        long profileId = getProfileIdForSession(oldSessionId);

        long sessionId = sessionService.createSession(
            title != null ? title : "Новая сессия",
            clientManager.getCurrentModel(),
            clientManager.getSystemMessage(),
            currentMode,
            profileId
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

        long profileId = getProfileIdForSession(id);

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
                    currentMode,
                    profileId
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

    private static void handleGetBranchStats(Context ctx) {
        long sessionId = Long.parseLong(ctx.pathParam("id"));
        long branchId = Long.parseLong(ctx.pathParam("branchId"));
        log.info("Get branch stats: session_id={}, branch_id={}", sessionId, branchId);
        
        var stats = sessionService.getBranchStats(sessionId, branchId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("stats", Map.of(
            "totalTokens", stats.totalTokens(),
            "totalCost", stats.totalCost(),
            "requestCount", stats.requestCount()
        ));
        ctx.json(response);
    }

    private static void handleGetStrategies(Context ctx) {
        try {
            var strategies = Arrays.stream(ContextStrategy.values())
                .map(s -> Map.of(
                    "name", s.name(),
                    "description", getStrategyDescription(s)
                ))
                .toList();
            ctx.json(Map.of("success", true, "strategies", strategies));
        } catch (Exception e) {
            log.error("Error getting strategies: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static String getStrategyDescription(ContextStrategy strategy) {
        return switch (strategy) {
            case NONE -> "Без управления контекстом - полная история";
            case COMPRESSION -> "Суммаризация - автоматическое сжатие старых сообщений";
            case SLIDING_WINDOW -> "Скользящее окно - только последние N сообщений";
            case STICKY_FACTS -> "Sticky Facts - ключевые факты + последние N сообщений";
            case BRANCHING -> "Ветки диалога - создание альтернативных веток от чекпоинта";
        };
    }

    private static void handleGetContextStrategy(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            ContextStrategy strategy = sessionService.getContextStrategy(sessionId);
            ctx.json(Map.of("success", true, "strategy", strategy.name()));
        } catch (Exception e) {
            log.error("Error getting context strategy: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleSetContextStrategy(Context ctx) {
        try {
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            String strategyStr = (String) request.get("strategy");
            
            if (strategyStr == null) {
                ctx.status(400).json(Map.of("success", false, "error", "Параметр 'strategy' обязателен"));
                return;
            }

            ContextStrategy strategy;
            try {
                strategy = ContextStrategy.valueOf(strategyStr);
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("success", false, "error", "Неверная стратегия: " + strategyStr));
                return;
            }

            long sessionId = Long.parseLong(ctx.pathParam("id"));

            if (strategy == ContextStrategy.BRANCHING) {
                sessionService.initializeBranchingStrategy(sessionId);
            }

            sessionService.updateContextStrategy(sessionId, strategy);

            ctx.json(Map.of("success", true, "message", "Стратегия контекста обновлена: " + strategy.name()));
        } catch (Exception e) {
            log.error("Error setting context strategy: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // Facts API
    private static void handleGetFacts(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            var facts = sessionService.getFacts(sessionId);
            ctx.json(Map.of("success", true, "facts", facts));
        } catch (Exception e) {
            log.error("Error getting facts: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleSaveFact(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            String category = (String) request.get("category");
            String key = (String) request.get("key");
            String value = (String) request.get("value");

            if (category == null || key == null || value == null) {
                ctx.status(400).json(Map.of("success", false, "error", "category, key, value обязательны"));
                return;
            }

            var fact = sessionService.saveFact(sessionId, category, key, value);
            ctx.json(Map.of("success", true, "fact", fact));
        } catch (Exception e) {
            log.error("Error saving fact: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleUpdateFact(Context ctx) {
        try {
            long factId = Long.parseLong(ctx.pathParam("factId"));
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            String category = (String) request.get("category");
            String key = (String) request.get("key");
            String value = (String) request.get("value");

            if (category == null || key == null || value == null) {
                ctx.status(400).json(Map.of("success", false, "error", "category, key, value обязательны"));
                return;
            }

            var fact = sessionService.updateFact(factId, category, key, value);
            ctx.json(Map.of("success", true, "fact", fact));
        } catch (Exception e) {
            log.error("Error updating fact: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleDeleteFact(Context ctx) {
        try {
            long factId = Long.parseLong(ctx.pathParam("factId"));
            sessionService.deleteFact(factId);
            ctx.json(Map.of("success", true, "message", "Fact deleted"));
        } catch (Exception e) {
            log.error("Error deleting fact: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleExtractFacts(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            sessionService.extractFactsFromLastMessage(sessionId);
            ctx.json(Map.of("success", true, "message", "Извлечение фактов запущено"));
        } catch (Exception e) {
            log.error("Error extracting facts: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // Sticky Facts Settings API
    private static void handleGetStickyFactsSettings(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            int windowSize = sessionService.getStickyFactsWindowSize(sessionId);
            ctx.json(Map.of("success", true, "stickyFactsWindowSize", windowSize));
        } catch (Exception e) {
            log.error("Error getting sticky facts settings: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleSetStickyFactsSettings(Context ctx) {
        try {
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            Integer windowSize = (Integer) request.get("stickyFactsWindowSize");

            if (windowSize == null || windowSize < 1 || windowSize > 100) {
                ctx.status(400).json(Map.of("success", false, "error", "stickyFactsWindowSize должен быть от 1 до 100"));
                return;
            }

            long sessionId = Long.parseLong(ctx.pathParam("id"));
            sessionService.updateStickyFactsWindowSize(sessionId, windowSize);
            
            ctx.json(Map.of("success", true, "message", "Sticky Facts window size обновлён: " + windowSize));
        } catch (Exception e) {
            log.error("Error setting sticky facts settings: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // Branches API
    private static void handleGetBranches(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            var branches = sessionService.getBranches(sessionId);
            var activeBranch = sessionService.getActiveBranch(sessionId);

            List<Map<String, Object>> branchList = new ArrayList<>();
            for (var b : branches) {
                Map<String, Object> branchMap = new HashMap<>();
                branchMap.put("id", b.id());
                branchMap.put("sessionId", b.sessionId());
                branchMap.put("name", b.name());
                branchMap.put("parentMessageId", b.parentMessageId());
                branchMap.put("createdAt", b.createdAt().toString());
                branchMap.put("isMain", b.isMain());
                branchMap.put("isActive", activeBranch != null && activeBranch.id() == b.id());
                branchList.add(branchMap);
            }

            ctx.json(Map.of("success", true, "branches", branchList));
        } catch (Exception e) {
            log.error("Error getting branches: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleCreateBranch(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            String name = (String) request.get("name");
            Long checkpointMessageId = request.get("checkpointMessageId") != null ?
                ((Number) request.get("checkpointMessageId")).longValue() : null;

            if (name == null || name.isBlank()) {
                ctx.status(400).json(Map.of("success", false, "error", "Параметр 'name' обязателен"));
                return;
            }

            var branch = sessionService.createBranch(sessionId, name, checkpointMessageId);
            ctx.json(Map.of("success", true, "branch", branch));
        } catch (Exception e) {
            log.error("Error creating branch: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleSwitchBranch(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            long branchId = Long.parseLong(ctx.pathParam("branchId"));

            sessionService.switchBranch(sessionId, branchId);
            ctx.json(Map.of("success", true, "message", "Ветка переключена"));
        } catch (Exception e) {
            log.error("Error switching branch: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleDeleteBranch(Context ctx) {
        try {
            long branchId = Long.parseLong(ctx.pathParam("branchId"));

            sessionService.deleteBranch(branchId);
            ctx.json(Map.of("success", true, "message", "Ветка удалена"));
        } catch (Exception e) {
            log.error("Error deleting branch: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // Compression Settings API
    private static void handleGetCompressionSettings(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            int keepMessages = sessionService.getCompressionKeepMessages(sessionId);
            int summaryInterval = sessionService.getCompressionSummaryInterval(sessionId);
            ctx.json(Map.of("success", true, 
                "compressionKeepMessages", keepMessages,
                "compressionSummaryInterval", summaryInterval));
        } catch (Exception e) {
            log.error("Error getting compression settings: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleSetCompressionSettings(Context ctx) {
        try {
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            Integer keepMessages = (Integer) request.get("compressionKeepMessages");
            Integer summaryInterval = (Integer) request.get("compressionSummaryInterval");

            if (keepMessages == null || keepMessages < 1 || keepMessages > 100) {
                ctx.status(400).json(Map.of("success", false, "error", "compressionKeepMessages должен быть от 1 до 100"));
                return;
            }

            if (summaryInterval == null || summaryInterval < 1 || summaryInterval > 100) {
                ctx.status(400).json(Map.of("success", false, "error", "compressionSummaryInterval должен быть от 1 до 100"));
                return;
            }

            long sessionId = Long.parseLong(ctx.pathParam("id"));
            sessionService.updateCompressionSettings(sessionId, keepMessages, summaryInterval);
            
            ctx.json(Map.of("success", true, "message", "Compression settings обновлены"));
        } catch (Exception e) {
            log.error("Error setting compression settings: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // Sliding Window Settings API
    private static void handleGetSlidingWindowSettings(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            int windowSize = sessionService.getSlidingWindowSize(sessionId);
            ctx.json(Map.of("success", true, "slidingWindowSize", windowSize));
        } catch (Exception e) {
            log.error("Error getting sliding window settings: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleSetSlidingWindowSettings(Context ctx) {
        try {
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            Integer windowSize = (Integer) request.get("slidingWindowSize");

            if (windowSize == null || windowSize < 1 || windowSize > 100) {
                ctx.status(400).json(Map.of("success", false, "error", "slidingWindowSize должен быть от 1 до 100"));
                return;
            }

            long sessionId = Long.parseLong(ctx.pathParam("id"));
            sessionService.updateSlidingWindowSize(sessionId, windowSize);
            
            ctx.json(Map.of("success", true, "message", "Sliding Window size обновлён: " + windowSize));
        } catch (Exception e) {
            log.error("Error setting sliding window settings: {}", e.getMessage());
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
        public Long id;

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

        public ChatMessage(String role, String content, int inputTokens, int outputTokens, int latency, double cost, Long id) {
            this.role = role;
            this.content = content;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.latency = latency;
            this.cost = cost;
            this.id = id;
        }

        // Getters для JSON сериализации
        public String getRole() { return role; }
        public String getContent() { return content; }
        public Integer getInputTokens() { return inputTokens; }
        public Integer getOutputTokens() { return outputTokens; }
        public Integer getLatency() { return latency; }
        public Double getCost() { return cost; }
        public Long getId() { return id; }
    }

    private static void validateMemoryKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new IllegalArgumentException("Key must be 1-100 characters");
        }
        if (!key.matches("^[a-zA-Z0-9_\\-\\.]+$")) {
            throw new IllegalArgumentException("Key contains invalid characters");
        }
    }

    private static void validateMemoryValue(String value) {
        if (value == null || value.length() > 10_000) {
            throw new IllegalArgumentException("Value must be 1-10,000 characters");
        }
    }

    // ==================== PROFILES API ====================

    private static void handleGetProfiles(Context ctx) {
        try {
            var profiles = profileRepository.getAll();
            ctx.json(Map.of("success", true, "profiles", profiles));
        } catch (Exception e) {
            log.error("Error getting profiles: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleCreateProfile(Context ctx) {
        try {
            var request = ctx.bodyAsClass(ProfileRequest.class);
            long id = profileRepository.create(request.name(), request.description(), request.systemPrompt(), request.settings());
            ctx.json(Map.of("success", true, "profileId", id));
        } catch (Exception e) {
            log.error("Error creating profile: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleGetProfile(Context ctx) {
        try {
            long id = Long.parseLong(ctx.pathParam("id"));
            var profile = profileRepository.getById(id);
            if (profile.isPresent()) {
                ctx.json(Map.of("success", true, "profile", profile.get()));
            } else {
                ctx.status(404).json(Map.of("success", false, "error", "Profile not found"));
            }
        } catch (Exception e) {
            log.error("Error getting profile: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleUpdateProfile(Context ctx) {
        try {
            long id = Long.parseLong(ctx.pathParam("id"));
            var request = ctx.bodyAsClass(ProfileRequest.class);
            profileRepository.update(id, request.name(), request.description(), request.systemPrompt(), request.settings());
            ctx.json(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error updating profile: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleDeleteProfile(Context ctx) {
        try {
            long id = Long.parseLong(ctx.pathParam("id"));
            var defaultProfile = profileRepository.getDefaultProfile();
            if (defaultProfile.isPresent() && defaultProfile.get().id() == id) {
                ctx.status(400).json(Map.of("success", false, "error", "Cannot delete default profile"));
                return;
            }
            profileRepository.delete(id);
            ctx.json(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error deleting profile: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleGetDefaultProfile(Context ctx) {
        try {
            var profile = profileRepository.getDefaultProfile();
            if (profile.isPresent()) {
                ctx.json(Map.of("success", true, "profile", profile.get()));
            } else {
                ctx.status(404).json(Map.of("success", false, "error", "Default profile not found"));
            }
        } catch (Exception e) {
            log.error("Error getting default profile: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleSetSessionProfile(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            long profileId = Long.parseLong(ctx.pathParam("profileId"));
            var profile = profileRepository.getById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found"));
            sessionService.updateSessionProfile(sessionId, profileId, profile.systemPrompt());
            ctx.json(Map.of("success", true, "profileId", profileId));
        } catch (Exception e) {
            log.error("Error setting session profile: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    // ==================== MEMORY API ====================

    private static void handleGetWorkingMemory(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            var memory = memoryService.getWorkingMemory(sessionId);
            ctx.json(Map.of("success", true, "memory", memory));
        } catch (Exception e) {
            log.error("Error getting working memory: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleSaveWorkingMemory(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            var request = ctx.bodyAsClass(MemoryRequest.class);
            validateMemoryKey(request.key());
            validateMemoryValue(request.value());
            var scope = com.example.deepseek.memory.MemoryScope.ofSession(sessionId);
            memoryService.save(scope, request.category(), request.key(), request.value(), com.example.deepseek.memory.MemoryLayer.WORKING);
            ctx.json(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error saving working memory: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleUpdateWorkingMemory(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            String key = ctx.pathParam("key");
            var request = ctx.bodyAsClass(MemoryRequest.class);
            validateMemoryKey(key);
            validateMemoryValue(request.value());
            var scope = com.example.deepseek.memory.MemoryScope.ofSession(sessionId);
            memoryService.save(scope, request.category(), key, request.value(), com.example.deepseek.memory.MemoryLayer.WORKING);
            ctx.json(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating working memory: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleDeleteWorkingMemory(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            String key = ctx.pathParam("key");
            var scope = com.example.deepseek.memory.MemoryScope.ofSession(sessionId);
            memoryService.deleteFromMemory(scope, key, com.example.deepseek.memory.MemoryLayer.WORKING);
            ctx.json(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error deleting working memory: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleGetLongTermMemory(Context ctx) {
        try {
            long profileId = Long.parseLong(ctx.pathParam("id"));
            var memory = memoryService.getLongTermMemory(profileId);
            ctx.json(Map.of("success", true, "memory", memory));
        } catch (Exception e) {
            log.error("Error getting long term memory: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleSaveLongTermMemory(Context ctx) {
        try {
            long profileId = Long.parseLong(ctx.pathParam("id"));
            var request = ctx.bodyAsClass(MemoryRequest.class);
            validateMemoryKey(request.key());
            validateMemoryValue(request.value());
            var scope = com.example.deepseek.memory.MemoryScope.ofProfile(profileId);
            memoryService.save(scope, request.category(), request.key(), request.value(), com.example.deepseek.memory.MemoryLayer.LONG_TERM);
            ctx.json(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error saving long term memory: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleUpdateLongTermMemory(Context ctx) {
        try {
            long profileId = Long.parseLong(ctx.pathParam("id"));
            String key = ctx.pathParam("key");
            var request = ctx.bodyAsClass(MemoryRequest.class);
            validateMemoryKey(key);
            validateMemoryValue(request.value());
            var scope = com.example.deepseek.memory.MemoryScope.ofProfile(profileId);
            memoryService.save(scope, request.category(), key, request.value(), com.example.deepseek.memory.MemoryLayer.LONG_TERM);
            ctx.json(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating long term memory: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleDeleteLongTermMemory(Context ctx) {
        try {
            long profileId = Long.parseLong(ctx.pathParam("id"));
            String key = ctx.pathParam("key");
            var scope = com.example.deepseek.memory.MemoryScope.ofProfile(profileId);
            memoryService.deleteFromMemory(scope, key, com.example.deepseek.memory.MemoryLayer.LONG_TERM);
            ctx.json(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error deleting long term memory: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleSuggestMemory(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            sessionService.onMessageSaved(sessionId, "user", "triggered manually");
            ctx.json(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error suggesting memory: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleGetMemorySuggestions(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            var suggestions = sessionService.getSuggestions(sessionId);
            ctx.json(Map.of("success", true, "suggestions", suggestions));
        } catch (Exception e) {
            log.error("Error getting memory suggestions: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleAnalyzeText(Context ctx) {
        try {
            var request = ctx.bodyAsClass(Map.class);
            String content = (String) request.get("content");
            var scope = new com.example.deepseek.memory.MemoryScope(null, null);
            var suggestions = memoryExtractionAgent.analyze(content, scope);
            ctx.json(Map.of("success", true, "suggestions", suggestions));
        } catch (Exception e) {
            log.error("Error analyzing text: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleMarkSuggestionsViewed(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            sessionService.markSuggestionsAsViewed(sessionId);
            ctx.json(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error marking suggestions as viewed: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

}
