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
import com.example.deepseek.dto.Message;
import com.example.deepseek.memory.MemoryService;
import com.example.deepseek.memory.agent.MemoryExtractionAgent;
import com.example.deepseek.memory.dto.MemorySuggestion;
import com.example.deepseek.memory.repository.ProfileRepository;
import com.example.deepseek.memory.repository.impl.ProfileRepositoryImpl;
import com.example.deepseek.memory.repository.impl.WorkingMemoryRepositoryImpl;
import com.example.deepseek.memory.repository.impl.LongTermMemoryRepositoryImpl;
import com.example.deepseek.dto.MemoryRequest;
import com.example.deepseek.dto.ProfileRequest;
import com.example.deepseek.task.TaskService;
import com.example.deepseek.task.TaskState;
import com.example.deepseek.task.TaskDto;
import com.example.deepseek.task.TaskOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.json.JavalinJackson;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
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
    private static TaskService taskService;
    private static com.example.deepseek.task.TaskManagerAgent taskManagerAgent;

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
        sessionService.setProfileRepository(profileRepository);
        WorkingMemoryRepositoryImpl workingMemoryRepo = new WorkingMemoryRepositoryImpl();
        LongTermMemoryRepositoryImpl longTermMemoryRepo = new LongTermMemoryRepositoryImpl();
        memoryService = new MemoryService(workingMemoryRepo, longTermMemoryRepo, sessionService.getSessionRepository());
        memoryExtractionAgent = new MemoryExtractionAgent(clientManager);
        sessionService.setMemoryExtractionAgent(memoryExtractionAgent);

        taskService = new TaskService(clientManager, sessionService);
        taskManagerAgent = new com.example.deepseek.task.TaskManagerAgent(clientManager);

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

        // ==================== TASKS API ====================
        app.get("/api/sessions/{id}/tasks", WebApp::handleGetTasks);
        app.post("/api/sessions/{id}/tasks", WebApp::handleCreateTask);
        app.get("/api/sessions/{id}/tasks/{taskId}", WebApp::handleGetTask);
        app.put("/api/sessions/{id}/tasks/{taskId}", WebApp::handleUpdateTask);
        app.delete("/api/sessions/{id}/tasks/{taskId}", WebApp::handleDeleteTask);
        app.post("/api/sessions/{id}/tasks/{taskId}/transition", WebApp::handleTransitionTask);
        app.post("/api/sessions/{id}/tasks/{taskId}/pause", WebApp::handlePauseTask);
        app.post("/api/sessions/{id}/tasks/{taskId}/resume", WebApp::handleResumeTask);

        // Task Context API
        app.post("/api/sessions/{id}/tasks/create-with-plan", WebApp::handleCreateTaskWithPlan);
        app.get("/api/sessions/{id}/tasks/{taskId}/context", WebApp::handleGetTaskContext);
        app.post("/api/sessions/{id}/tasks/{taskId}/increment-step", WebApp::handleIncrementStep);
        app.post("/api/sessions/{id}/tasks/{taskId}/add-done", WebApp::handleAddDone);
        app.post("/api/sessions/{id}/tasks/{taskId}/update-current", WebApp::handleUpdateCurrent);
        app.post("/api/sessions/{id}/tasks/{taskId}/validate-and-transition", WebApp::handleValidateAndTransition);
        app.post("/api/sessions/{id}/tasks/{taskId}/confirm-plan", WebApp::handleConfirmPlan);
        app.post("/api/sessions/{id}/tasks/{taskId}/replan", WebApp::handleReplanTask);

        app.get("/api/sessions/{id}/active-task", WebApp::handleGetActiveTask);

        app.get("/api/sessions/{id}/tasks/{taskId}/messages", WebApp::handleGetTaskMessages);
        app.get("/api/sessions/{id}/tasks/{taskId}/messages/{state}", WebApp::handleGetTaskMessagesByState);

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

            var activeTask = taskService.getActiveTask(sessionId);
            log.info("Active task for session {}: {}", sessionId, activeTask.isPresent() ? activeTask.get().title() + " (state: " + activeTask.get().state() + ")" : "none");

            var activeContext = activeTask.flatMap(t -> {
                try {
                    Optional<com.example.deepseek.task.TaskContext> taskCtx = taskService.getTaskContext(t.id());
                    if (taskCtx.isPresent()) {
                        log.info("Task context found for task {}: state={}, step={}/{}",
                            t.id(), taskCtx.get().state(), taskCtx.get().step(), taskCtx.get().total());
                    }
                    return taskCtx;
                } catch (Exception e) {
                    log.error("Error getting task context for task {}: {}", t.id(), e.getMessage());
                    return java.util.Optional.empty();
                }
            });

            var analysis = taskManagerAgent.analyze(message, activeContext);

            java.util.Optional<com.example.deepseek.task.TaskContext> finalContext = activeContext;
            String finalPrompt = message;
            boolean skipLLMCall = false;

            if (analysis.needsTask() && activeContext.isEmpty()) {
                log.info("Creating new task: description={}", analysis.description());
                var result = taskService.createTaskWithPlan(sessionId, "Задача из чата", analysis.description());

                String planNoteContent = generateTaskNote(result.planMessage(), 0);

                Map<String, Object> responseMap = new HashMap<>();
                responseMap.put("response", "Задача создана. Подтвердите план для начала выполнения.");
                responseMap.put("success", true);
                responseMap.put("taskCreated", true);
                responseMap.put("taskId", result.task().id());
                responseMap.put("requiresConfirmation", true);
                responseMap.put("taskPlanMessage", planNoteContent);

                ctx.json(responseMap);
                return;
            }

            if (activeTask.isPresent()) {
                var task = activeTask.get();
                Optional<com.example.deepseek.task.TaskContext> taskCtxOpt = taskService.getTaskContext(task.id());

                if (taskCtxOpt.isPresent()) {
                    com.example.deepseek.task.TaskContext taskCtx = taskCtxOpt.get();

                    if (taskCtx.state() == com.example.deepseek.task.TaskState.PLANNING) {
                        Map<String, Object> responseMap = new HashMap<>();
                        responseMap.put("response", "Задача находится в состоянии планирования. Пожалуйста, подтвердите план для начала выполнения.");
                        responseMap.put("success", true);
                        responseMap.put("taskState", "PLANNING");
                        responseMap.put("requiresConfirmation", true);

                        ctx.json(responseMap);
                        return;
                    }

                    if (taskCtx.state() == com.example.deepseek.task.TaskState.EXECUTION) {
                        log.info("Using buildStepPrompt for EXECUTION state. Message: {}", message);
                        log.info("Task context: step={}/{}, current={}", taskCtx.step(), taskCtx.total(), taskCtx.current());
                        finalPrompt = taskService.buildStepPrompt(message, taskCtx);
                        log.info("Generated step prompt: {}", finalPrompt);
                        finalContext = taskCtxOpt;
                    }

                    if (taskCtx.state() == com.example.deepseek.task.TaskState.DONE) {
                        String summary = taskService.generateTaskSummary(task.id());
                        Map<String, Object> responseMap = new HashMap<>();
                        responseMap.put("response", summary);
                        responseMap.put("success", true);
                        responseMap.put("taskState", "DONE");
                        responseMap.put("taskCompleted", true);

                        ctx.json(responseMap);
                        return;
                    }
                }
            }

            if (finalContext.isPresent()) {
                finalPrompt = taskService.buildPrompt(message, finalContext.get());
            }

            log.info("Sending prompt to LLM: {}", finalPrompt);
            String response = clientManager.chat(sessionId, finalPrompt, systemMessage);
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

            if (activeTask.isPresent()) {
                try {
                    taskService.validateAndAdvance(activeTask.get().id(), response, sessionId);
                    taskService.updateTaskAfterResponse(activeTask.get().id(), response);
                } catch (Exception e) {
                    log.error("Failed to update task after response: {}", e.getMessage());
                }
            }

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
        long sessionId = sessionService.getCurrentSessionId();

        List<MessageDto> sessionMessages = sessionService.getSessionMessages(sessionId);

        List<ChatMessage> history = new ArrayList<>();
        for (var msg : sessionMessages) {
            history.add(new ChatMessage(msg.role(), msg.content(),
                msg.inputTokens(), msg.outputTokens(), msg.latency(), msg.cost(), msg.id(), false, null, null, msg.createdAt()));
        }

        boolean requiresConfirmation = false;
        long activeTaskId = 0;

        try {
            var activeTask = taskService.getActiveTask(sessionId);
            var taskForMessages = activeTask;
            
            log.info("[handleHistory] Active task lookup: found={}", activeTask.isPresent());
            
            if (taskForMessages.isEmpty()) {
                taskForMessages = taskService.getLatestTask(sessionId);
                log.info("[handleHistory] No active task, using latest task: {}", 
                    taskForMessages.isPresent() ? taskForMessages.get().id() + " (state=" + taskForMessages.get().state() + ")" : "none");
            } else {
                log.info("[handleHistory] Active task found: id={}, state={}", taskForMessages.get().id(), taskForMessages.get().state());
            }
            
            if (taskForMessages.isPresent()) {
                activeTaskId = taskForMessages.get().id();
                var taskMessages = taskService.getTaskMessageRepository().getByTaskId(activeTaskId);
                log.info("[handleHistory] Found {} task messages for task {}", taskMessages.size(), activeTaskId);

                int totalSteps = 0;
                try {
                    var taskCtxOpt = taskService.getTaskContext(activeTaskId);
                    if (taskCtxOpt.isPresent()) {
                        totalSteps = taskCtxOpt.get().total();
                    }
                } catch (Exception e) {
                    log.error("[handleHistory] Error getting task context: {}", e.getMessage());
                }

                for (var taskMsg : taskMessages) {
                    String noteContent = generateTaskNote(taskMsg, totalSteps);
                    history.add(new ChatMessage("system", noteContent, 0, 0, 0, 0.0, null,
                        true, activeTaskId, taskMsg.taskState().name(), taskMsg.createdAt()));
                }

                if (taskForMessages.get().state() == TaskState.PLANNING) {
                    requiresConfirmation = true;
                    log.info("[handleHistory] Task {} is in PLANNING state, requiresConfirmation=true", activeTaskId);
                } else {
                    log.info("[handleHistory] Task {} is in {} state, requiresConfirmation=false", activeTaskId, taskForMessages.get().state());
                }
            }
        } catch (Exception e) {
            log.error("[handleHistory] Error loading task messages: {}", e.getMessage());
        }

        history.sort((a, b) -> {
            java.time.LocalDateTime timeA = a.getCreatedAt();
            java.time.LocalDateTime timeB = b.getCreatedAt();
            if (timeA == null) return 1;
            if (timeB == null) return -1;
            return timeA.compareTo(timeB);
        });

        log.info("Get history: session_id={}, message_count={}", sessionId, history.size());
        ctx.json(Map.of(
            "history", history,
            "mode", currentMode,
            "modeName", currentMode == 1 ? "Тестировщик" : "Помощник",
            "taskRequiresConfirmation", requiresConfirmation,
            "activeTaskId", activeTaskId
        ));
    }

    private static String generateTaskNote(com.example.deepseek.task.TaskMessageDto taskMsg, int totalSteps) {
        String icon = switch (taskMsg.taskState()) {
            case PLANNING -> "📋";
            case EXECUTION -> "⚡";
            case VALIDATION -> "✅";
            case DONE -> "✨";
        };

        String response = taskMsg.response();
        String stateLabel = taskMsg.taskState().name();

        if (taskMsg.taskState() == com.example.deepseek.task.TaskState.PLANNING) {
            try {
                var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var plan = objectMapper.readValue(response, java.util.List.class);
                int planSize = plan.size();
                String planPreview = planSize <= 5 ? String.join("\n", plan.stream().map(Object::toString).toList())
                    : "Шаги выполнения: " + String.join(", ", plan.subList(0, 5).stream().map(Object::toString).toList()) + "...";
                return String.format("%s [%s] Создан план из %d шагов\n%s", icon, stateLabel, planSize, planPreview);
            } catch (Exception e) {
                log.error("Failed to parse plan from response: {}", e.getMessage());
            }
        }

        if (taskMsg.taskState() == com.example.deepseek.task.TaskState.EXECUTION) {
            if (taskMsg.stepIndex() != null && totalSteps > 0) {
                stateLabel = String.format("EXECUTION %d/%d", taskMsg.stepIndex(), totalSteps);
            }
        }

        if (taskMsg.taskState() == com.example.deepseek.task.TaskState.VALIDATION) {
            if (response.contains("\"success\":true")) {
                icon = "✅";
            } else {
                icon = "⚠️";
            }
        }

        return String.format("%s [%s] %s", icon, stateLabel,
            response.length() > 200 ? response.substring(0, 200) + "..." : response);
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
        public Boolean isTaskNote;
        public Long taskId;
        public String taskState;
        public java.time.LocalDateTime createdAt;

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

        public ChatMessage(String role, String content, int inputTokens, int outputTokens, int latency, double cost, Long id,
                           Boolean isTaskNote, Long taskId, String taskState) {
            this.role = role;
            this.content = content;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.latency = latency;
            this.cost = cost;
            this.id = id;
            this.isTaskNote = isTaskNote;
            this.taskId = taskId;
            this.taskState = taskState;
        }

        public ChatMessage(String role, String content, int inputTokens, int outputTokens, int latency, double cost, Long id,
                           Boolean isTaskNote, Long taskId, String taskState, java.time.LocalDateTime createdAt) {
            this.role = role;
            this.content = content;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.latency = latency;
            this.cost = cost;
            this.id = id;
            this.isTaskNote = isTaskNote;
            this.taskId = taskId;
            this.taskState = taskState;
            this.createdAt = createdAt;
        }

        // Getters для JSON сериализации
        public String getRole() { return role; }
        public String getContent() { return content; }
        public Integer getInputTokens() { return inputTokens; }
        public Integer getOutputTokens() { return outputTokens; }
        public Integer getLatency() { return latency; }
        public Double getCost() { return cost; }
        public Long getId() { return id; }
        public Boolean getIsTaskNote() { return isTaskNote; }
        public Long getTaskId() { return taskId; }
        public String getTaskState() { return taskState; }
        public java.time.LocalDateTime getCreatedAt() { return createdAt; }
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
            long id = profileRepository.create(request.name(), request.description(), request.systemPrompt(), request.personalization());
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
            profileRepository.update(id, request.name(), request.description(), request.systemPrompt(), request.personalization());
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

    // ==================== TASKS API ====================

    private static void handleGetTasks(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            var tasks = taskService.getTasksBySession(sessionId);
            ctx.json(Map.of("success", true, "tasks", tasks));
        } catch (Exception e) {
            log.error("Error getting tasks: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleCreateTask(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            String title = (String) request.get("title");
            String description = (String) request.get("description");
            String stateStr = (String) request.get("state");

            if (title == null || title.isBlank()) {
                ctx.status(400).json(Map.of("success", false, "error", "Параметр 'title' обязателен"));
                return;
            }

            TaskState initialState = TaskState.PLANNING;
            if (stateStr != null && !stateStr.isBlank()) {
                try {
                    initialState = TaskState.valueOf(stateStr);
                } catch (IllegalArgumentException e) {
                    ctx.status(400).json(Map.of("success", false, "error", "Неверное состояние: " + stateStr));
                    return;
                }
            }

            var task = taskService.createTask(sessionId, title, description, initialState);
            ctx.json(Map.of("success", true, "task", task));
        } catch (Exception e) {
            log.error("Error creating task: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleGetTask(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            var task = taskService.getTask(taskId);
            if (task.isPresent()) {
                ctx.json(Map.of("success", true, "task", task.get()));
            } else {
                ctx.status(404).json(Map.of("success", false, "error", "Task not found"));
            }
        } catch (Exception e) {
            log.error("Error getting task: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleUpdateTask(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            String title = (String) request.get("title");
            String description = (String) request.get("description");

            if (title == null || title.isBlank()) {
                ctx.status(400).json(Map.of("success", false, "error", "Параметр 'title' обязателен"));
                return;
            }

            var task = taskService.updateTask(taskId, title, description);
            ctx.json(Map.of("success", true, "task", task));
        } catch (Exception e) {
            log.error("Error updating task: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleDeleteTask(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            taskService.deleteTask(taskId);
            ctx.json(Map.of("success", true, "message", "Task deleted"));
        } catch (Exception e) {
            log.error("Error deleting task: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleTransitionTask(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            String stateStr = (String) request.get("state");
            String expectedAction = (String) request.get("expectedAction");

            if (stateStr == null || stateStr.isBlank()) {
                ctx.status(400).json(Map.of("success", false, "error", "Параметр 'state' обязателен"));
                return;
            }

            TaskState newState;
            try {
                newState = TaskState.valueOf(stateStr);
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("success", false, "error", "Неверное состояние: " + stateStr));
                return;
            }

            var task = taskService.transitionTask(taskId, newState, expectedAction);
            ctx.json(Map.of("success", true, "task", task));
        } catch (IllegalStateException e) {
            log.error("Invalid task transition: {}", e.getMessage());
            ctx.status(400).json(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error transitioning task: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handlePauseTask(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            String reason = (String) request.get("reason");

            var task = taskService.pauseTask(taskId, reason);
            ctx.json(Map.of("success", true, "task", task));
        } catch (Exception e) {
            log.error("Error pausing task: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleResumeTask(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            var task = taskService.resumeTask(taskId);
            ctx.json(Map.of("success", true, "task", task));
        } catch (Exception e) {
            log.error("Error resuming task: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleCreateTaskWithPlan(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            String title = (String) request.get("title");
            String description = (String) request.get("description");

            if (title == null || title.isBlank()) {
                ctx.status(400).json(Map.of("success", false, "error", "Параметр 'title' обязателен"));
                return;
            }

            if (description == null || description.isBlank()) {
                ctx.status(400).json(Map.of("success", false, "error", "Параметр 'description' обязателен"));
                return;
            }

            var result = taskService.createTaskWithPlan(sessionId, title, description);
            String planNoteContent = generateTaskNote(result.planMessage(), 0);
            ctx.json(Map.of(
                "success", true,
                "task", result.task(),
                "planMessage", planNoteContent
            ));
        } catch (Exception e) {
            log.error("Error creating task with plan: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleGetTaskContext(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            var context = taskService.getTaskContext(taskId);
            if (context.isPresent()) {
                ctx.json(Map.of("success", true, "context", context.get()));
            } else {
                ctx.status(404).json(Map.of("success", false, "error", "Task context not found"));
            }
        } catch (Exception e) {
            log.error("Error getting task context: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleIncrementStep(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            taskService.incrementStep(taskId);
            ctx.json(Map.of("success", true, "message", "Step incremented"));
        } catch (Exception e) {
            log.error("Error incrementing step: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleAddDone(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            String step = (String) request.get("step");

            if (step == null || step.isBlank()) {
                ctx.status(400).json(Map.of("success", false, "error", "Параметр 'step' обязателен"));
                return;
            }

            taskService.addDone(taskId, step);
            ctx.json(Map.of("success", true, "message", "Step added to done"));
        } catch (Exception e) {
            log.error("Error adding done: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleUpdateCurrent(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            String current = (String) request.get("current");

            if (current == null || current.isBlank()) {
                ctx.status(400).json(Map.of("success", false, "error", "Параметр 'current' обязателен"));
                return;
            }

            taskService.updateCurrent(taskId, current);
            ctx.json(Map.of("success", true, "message", "Current step updated"));
        } catch (Exception e) {
            log.error("Error updating current: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleValidateAndTransition(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            String currentResult = (String) request.get("currentResult");

            if (currentResult == null || currentResult.isBlank()) {
                ctx.status(400).json(Map.of("success", false, "error", "Параметр 'currentResult' обязателен"));
                return;
            }

            var result = taskService.validateAndTransition(taskId, currentResult);
            ctx.json(Map.of("success", true, "result", result));
        } catch (Exception e) {
            log.error("Error validating and transitioning: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleGetActiveTask(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            var activeTask = taskService.getActiveTask(sessionId);
            if (activeTask.isPresent()) {
                ctx.json(Map.of("success", true, "task", activeTask.get()));
            } else {
                ctx.json(Map.of("success", true, "task", ""));
            }
        } catch (Exception e) {
            log.error("Error getting active task: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleConfirmPlan(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            long sessionId = sessionService.getCurrentSessionId();

            TaskDto task = taskService.getTask(taskId).orElseThrow(
                () -> new IllegalArgumentException("Task not found: " + taskId)
            );

            log.info("[handleConfirmPlan] START: taskId={}, state={}, paused={}", taskId, task.state(), task.paused());

            if (task.state() != TaskState.PLANNING) {
                log.warn("[handleConfirmPlan] Task not in PLANNING state. Current state: {}", task.state());
                ctx.status(400).json(Map.of("success", false,
                    "error", "Задача не находится в состоянии планирования. Текущее состояние: " + task.state().getDisplayName()));
                return;
            }

            log.info("[handleConfirmPlan] Transitioning task {} to EXECUTION", taskId);
            taskService.transitionTask(taskId, TaskState.EXECUTION, null, sessionId);
            
            TaskDto afterExecution = taskService.getTask(taskId).orElseThrow();
            log.info("[handleConfirmPlan] Task {} state after EXECUTION transition: {}", taskId, afterExecution.state());

            ctx.json(Map.of(
                "success", true,
                "message", "Выполнение начато",
                "taskId", taskId,
                "taskState", afterExecution.state().name()
            ));

            new Thread(() -> {
                try {
                    log.info("[AsyncExecution] Starting async execution for task {}", taskId);
                    List<StepResult> allResults = executeAllSteps(taskId, sessionId);
                    log.info("[AsyncExecution] Executed {} steps for task {}", allResults.size(), taskId);
                    
                    String finalResult = validateAndCompleteTask(taskId, sessionId, allResults);
                    log.info("[AsyncExecution] Final result for task {}: {}", taskId, 
                        finalResult.length() > 100 ? finalResult.substring(0, 100) + "..." : finalResult);

                    TaskDto finalTask = taskService.getTask(taskId).orElseThrow();
                    log.info("[AsyncExecution] END: Task {} final state: {}", taskId, finalTask.state());
                } catch (Exception e) {
                    log.error("[AsyncExecution] Error executing task {}: {}", taskId, e.getMessage(), e);
                    try {
                        taskService.transitionTask(taskId, TaskState.PLANNING, "Ошибка выполнения: " + e.getMessage(), sessionId);
                    } catch (SQLException ex) {
                        log.error("[AsyncExecution] Failed to transition task to PLANNING: {}", ex.getMessage());
                    }
                }
            }, "task-execution-" + taskId).start();

        } catch (IllegalArgumentException e) {
            log.error("[handleConfirmPlan] Invalid request: {}", e.getMessage());
            ctx.status(400).json(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("[handleConfirmPlan] Error: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleReplanTask(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            long sessionId = sessionService.getCurrentSessionId();
            
            TaskDto task = taskService.getTask(taskId).orElseThrow(
                () -> new IllegalArgumentException("Task not found: " + taskId)
            );
            
            log.info("Replanning task id={}, current state={}", taskId, task.state());
            
            taskService.transitionTask(taskId, TaskState.PLANNING, "Возврат к планированию по запросу пользователя", sessionId);
            
            ctx.json(Map.of("success", true, "message", "Задача возвращена в планирование"));
        } catch (Exception e) {
            log.error("Error replanning task: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleGetTaskMessages(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            var messages = taskService.getTaskMessageRepository().getByTaskId(taskId);
            ctx.json(Map.of("success", true, "messages", messages));
        } catch (Exception e) {
            log.error("Error getting task messages: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static void handleGetTaskMessagesByState(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            String stateStr = ctx.pathParam("state");
            TaskState taskState = TaskState.valueOf(stateStr);

            var messages = taskService.getTaskMessageRepository().getAllByTaskIdAndState(taskId, taskState);
            ctx.json(Map.of("success", true, "messages", messages));
        } catch (IllegalArgumentException e) {
            log.error("Invalid task state: {}", e.getMessage());
            ctx.status(400).json(Map.of("success", false, "error", "Неверное состояние: " + ctx.pathParam("state")));
        } catch (Exception e) {
            log.error("Error getting task messages by state: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private static String executeTaskStep(long taskId, long sessionId) {
        try {
            log.info("Executing first step for task {}", taskId);

            var taskCtxOpt = taskService.getTaskContext(taskId);
            if (taskCtxOpt.isEmpty()) {
                throw new IllegalArgumentException("Task context not found: " + taskId);
            }

            com.example.deepseek.task.TaskContext taskCtx = taskCtxOpt.get();
            String taskPrompt = taskService.buildPrompt(taskCtx.task(), taskCtx);

            log.info("Generated task prompt for task {}: {}", taskId, taskPrompt);

            List<Message> messages = List.of(Message.user(taskPrompt));
            String response = clientManager.chatWithMessages(sessionId, messages);
            log.info("Received response from LLM for task {}: {}", taskId, response);

            taskService.getTaskMessageRepository().saveMessage(taskId, TaskState.EXECUTION, taskPrompt, response, 0);

            return response;
        } catch (Exception e) {
            log.error("Error executing task step: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to execute task step: " + e.getMessage(), e);
        }
    }

    private static record StepResult(
        int stepNumber,
        String description,
        String result
    ) {}

    private static List<StepResult> executeAllSteps(long taskId, long sessionId) {
        List<StepResult> allResults = new ArrayList<>();
        
        try {
            int stepCount = 0;
            while (true) {
                var taskCtxOpt = taskService.getTaskContext(taskId);
                if (taskCtxOpt.isEmpty()) {
                    log.error("Task context not found for task {}", taskId);
                    break;
                }

                com.example.deepseek.task.TaskContext taskCtx = taskCtxOpt.get();
                int currentStep = taskCtx.step();
                int totalSteps = taskCtx.total();
                String currentDescription = taskCtx.current();

                log.info("Executing step {}/{} for task {}: {}", currentStep, totalSteps, taskId, currentDescription);

                String userMessage = taskCtx.task();
                String taskPrompt = taskService.buildPrompt(userMessage, taskCtx);

                List<Message> messages = List.of(Message.user(taskPrompt));
                String response = clientManager.chatWithMessages(sessionId, messages);
                log.info("Received response for step {} from LLM: {}", currentStep, response.substring(0, Math.min(100, response.length())));

                taskService.getTaskMessageRepository().saveMessage(taskId, TaskState.EXECUTION, taskPrompt, response, 0, currentStep);

                allResults.add(new StepResult(currentStep, currentDescription, response));

                taskService.addDone(taskId, currentDescription);

                if (currentStep < totalSteps) {
                    taskService.incrementStep(taskId);
                    stepCount++;
                    log.info("Step {} completed, moving to step {}", currentStep, currentStep + 1);
                } else {
                    log.info("All steps completed for task {}", taskId);
                    break;
                }
            }
            
            log.info("Total steps executed for task {}: {}", taskId, allResults.size());
        } catch (Exception e) {
            log.error("Error executing all steps: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to execute all steps: " + e.getMessage(), e);
        }
        
        return allResults;
    }

    private static String validateAndCompleteTask(long taskId, long sessionId, List<StepResult> allResults) {
        try {
            log.info("[validateAndCompleteTask] Starting for task {}", taskId);

            String finalResult = combineAllResults(allResults);
            log.info("[validateAndCompleteTask] Combined result length: {}", finalResult.length());

            var taskCtxOpt = taskService.getTaskContext(taskId);
            if (taskCtxOpt.isEmpty()) {
                throw new IllegalArgumentException("Task context not found: " + taskId);
            }

            com.example.deepseek.task.TaskContext taskCtx = taskCtxOpt.get();
            log.info("[validateAndCompleteTask] Task context: state={}, step={}/{}", 
                taskCtx.state(), taskCtx.step(), taskCtx.total());
            
            TaskOrchestrator.ValidationResult validationResult = 
                taskService.validateOnly(taskId, finalResult, sessionId);

            log.info("[validateAndCompleteTask] Validation result: success={}, suggestedNextState={}", 
                validationResult.success(), validationResult.nextState());

            taskService.getTaskMessageRepository().saveMessage(
                taskId, TaskState.VALIDATION, finalResult, 
                validationResult.message(), 0
            );

            TaskDto taskBeforeTransition = taskService.getTask(taskId).orElseThrow();
            log.info("[validateAndCompleteTask] Task state before transition: {}", taskBeforeTransition.state());

            if (validationResult.success()) {
                log.info("[validateAndCompleteTask] Validation SUCCESS, transitioning to DONE");
                taskService.transitionTask(taskId, TaskState.DONE, validationResult.message(), sessionId);
            } else {
                log.info("[validateAndCompleteTask] Validation FAILED, transitioning to PLANNING");
                taskService.transitionTask(taskId, TaskState.PLANNING, validationResult.message(), sessionId);
            }

            TaskDto taskAfterTransition = taskService.getTask(taskId).orElseThrow();
            log.info("[validateAndCompleteTask] Task state after transition: {}", taskAfterTransition.state());

            if (validationResult.success()) {
                return generateFinalMessage(allResults, validationResult.message());
            } else {
                return generateValidationFailedMessage(validationResult.message());
            }
        } catch (Exception e) {
            log.error("[validateAndCompleteTask] Error: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to validate task: " + e.getMessage(), e);
        }
    }

    private static String combineAllResults(List<StepResult> allResults) {
        StringBuilder combined = new StringBuilder();
        combined.append("Результат выполнения задачи:\n\n");
        for (StepResult step : allResults) {
            combined.append("Шаг ").append(step.stepNumber()).append(": ").append(step.description()).append("\n");
            combined.append(step.result()).append("\n\n");
        }
        return combined.toString();
    }

    private static String generateFinalMessage(List<StepResult> allResults, String validationMessage) {
        return String.format("✨ Задача выполнена!\n\nВыполнено шагов: %d\n\n%s", 
            allResults.size(), validationMessage
        );
    }

    private static String generateValidationFailedMessage(String validationMessage) {
        return String.format("⚠️ Валидация не пройдена\n\n%s\n\nХотите вернуться к планированию?", 
            validationMessage
        );
    }

}
