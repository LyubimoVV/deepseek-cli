package com.example.deepseek.app;

import com.example.deepseek.agent.FactsExtractionAgent;
import com.example.deepseek.agent.SummaryAgent;
import com.example.deepseek.client.*;
import com.example.deepseek.config.AppConfig;
import com.example.deepseek.context.ContextManager;
import com.example.deepseek.context.ContextScheduler;
import com.example.deepseek.context.ContextStrategyFactory;
import com.example.deepseek.context.strategies.*;
import com.example.deepseek.db.*;
import com.example.deepseek.memory.MemoryService;
import com.example.deepseek.memory.agent.MemoryExtractionAgent;
import com.example.deepseek.memory.repository.impl.*;
import com.example.deepseek.mcp.McpToolIntegrationService;
import com.example.deepseek.task.HeartbeatMonitor;
import com.example.deepseek.task.TaskManagerAgent;
import com.example.deepseek.task.TaskRecoveryService;
import com.example.deepseek.task.TaskService;
import com.example.deepseek.app.controllers.*;
import com.example.deepseek.invariant.InvariantService;
import com.example.deepseek.mcp.McpService;
import com.example.deepseek.mcp.McpSseProxyService;
import com.example.deepseek.embedding.EmbeddingService;
import com.example.deepseek.embedding.controllers.EmbeddingController;
import com.example.deepseek.embedding.ollama.OllamaClient;
import com.example.deepseek.embedding.ollama.RerankerClient;
import com.example.deepseek.embedding.repository.ChunkMetadataRepository;
import com.example.deepseek.rag.RagService;
import com.example.deepseek.rag.RerankerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.slf4j.LoggerFactory;

import java.util.List;

public class WebApp {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(WebApp.class);

    private static final int DEFAULT_PORT = 8080;
    private static final String DEEPSEEK_API_KEY_ENV = "DEEPSEEK_API_KEY";
    private static final String OPENROUTER_API_KEY_ENV = "OPENROUTER_API_KEY";

    public static void main(String[] args) {
        ClientManager clientManager = new ClientManager();
        SessionService sessionService = new SessionService();
        ContextManager contextManager = new ContextManager(sessionService.getSessionRepository());
        SummaryAgent summaryAgent = new SummaryAgent(clientManager, sessionService);
        GlobalSummaryRepository globalSummaryRepository = new GlobalSummaryRepository();

        FactsRepository factsRepository = new FactsRepository();
        FactsExtractionAgent factsExtractionAgent = new FactsExtractionAgent(clientManager, factsRepository);

        BranchRepository branchRepository = new BranchRepository();
        sessionService.setBranchRepository(branchRepository);

        sessionService.setSummaryAgent(summaryAgent);
        sessionService.setFactsRepository(factsRepository);
        sessionService.setFactsExtractionAgent(factsExtractionAgent);
        ContextScheduler contextScheduler = new ContextScheduler(summaryAgent, sessionService.getMessageRepository());
        sessionService.setContextScheduler(contextScheduler);

        var profileRepository = new ProfileRepositoryImpl();
        sessionService.setProfileRepository(profileRepository);
        var workingMemoryRepo = new WorkingMemoryRepositoryImpl();
        var longTermMemoryRepo = new LongTermMemoryRepositoryImpl();
        var memoryService = new MemoryService(workingMemoryRepo, longTermMemoryRepo, sessionService.getSessionRepository());
        var memoryExtractionAgent = new MemoryExtractionAgent(clientManager);
        sessionService.setMemoryExtractionAgent(memoryExtractionAgent);

        TaskService taskService = new TaskService(clientManager, sessionService);
        TaskManagerAgent taskManagerAgent = new TaskManagerAgent(clientManager);
        
        InvariantService invariantService = new InvariantService();
        taskService.setInvariantService(invariantService);

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

        ContextStrategyFactory strategyFactory = new ContextStrategyFactory(noneHandler, compressionHandler, slidingHandler, stickyFactsHandler, branchingHandler);
        sessionService.setStrategyFactory(strategyFactory);

        String deepSeekApiKey = System.getenv(DEEPSEEK_API_KEY_ENV);
        String openRouterApiKey = System.getenv(OPENROUTER_API_KEY_ENV);

        boolean hasDeepSeek = deepSeekApiKey != null && !deepSeekApiKey.isBlank();
        boolean hasOpenRouter = openRouterApiKey != null && !openRouterApiKey.isBlank();
        boolean hasOllama = OllamaChatClient.isOllamaAvailable();
        boolean hasLocalLlm = LocalLlmClient.isAvailable();

        if (!hasDeepSeek && !hasOpenRouter && !hasOllama && !hasLocalLlm) {
            log.error("Ошибка: Не установлена ни одна переменная окружения для API ключей и нет локальных LLM");
            log.error("Установите хотя бы один API ключ:");
            log.error("  set " + DEEPSEEK_API_KEY_ENV + "=your_deepseek_api_key");
            log.error("  или");
            log.error("  set " + OPENROUTER_API_KEY_ENV + "=your_openrouter_api_key");
            log.error("  или запустите Ollama: ollama serve");
            log.error("  или запустите py_local_llm_server");
            System.exit(1);
        }

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

        if (OllamaChatClient.isOllamaAvailable()) {
            log.info("✓ Ollama доступен (localhost:11434)");
            List<String> ollamaModels = OllamaChatClient.getAvailableModels();
            if (!ollamaModels.isEmpty()) {
                log.info("  Найдено {} локальных моделей: {}", ollamaModels.size(), ollamaModels);
                for (String modelName : ollamaModels) {
                    String modelId = "ollama:" + modelName;
                    OllamaChatClient ollamaClient = new OllamaChatClient(
                        modelName,
                        clientManager.getSystemMessage(),
                        strategyFactory,
                        sessionService.getSessionRepository()
                    );
                    clientManager.registerClient(modelId, ollamaClient);
                }
            } else {
                log.info("  Нет установленных моделей. Установите модель: ollama pull llama3.1:8b");
            }
        } else {
            log.info("⚠ Ollama не доступен. Запустите Ollama для использования локальных моделей.");
        }

        if (LocalLlmClient.isAvailable()) {
            String localLlmUrl = AppConfig.getLocalLlmUrl();
            log.info("✓ Free Ollama сервер доступен ({})", localLlmUrl);
            List<String> localLlmModels = LocalLlmClient.getAvailableModels();
            if (!localLlmModels.isEmpty()) {
                log.info("  Найдено {} моделей: {}", localLlmModels.size(), localLlmModels);
                for (String modelName : localLlmModels) {
                    String modelId = "free_ollama:" + modelName;
                    LocalLlmClient localClient = new LocalLlmClient(
                        modelName,
                        clientManager.getSystemMessage(),
                        strategyFactory,
                        sessionService.getSessionRepository()
                    );
                    clientManager.registerClient(modelId, localClient);
                }
            } else {
                log.info("  Нет моделей на сервере. Проверьте конфигурацию py_local_llm_server");
            }
        } else {
            log.info("⚠ Free Ollama сервер не доступен. Запустите py_local_llm_server");
        }

        String defaultModel;
        if (hasDeepSeek) {
            defaultModel = DeepSeekClient.MODEL_REASONER;
        } else if (hasOpenRouter) {
            defaultModel = OpenRouterClient.MODEL_GPT_OSS;
        } else if (hasOllama) {
            List<String> ollamaModels = OllamaChatClient.getAvailableModels();
            defaultModel = "ollama:" + (ollamaModels.isEmpty() ? "llama3.1:8b" : ollamaModels.get(0));
        } else if (hasLocalLlm) {
            List<String> localLlmModels = LocalLlmClient.getAvailableModels();
            defaultModel = "free_ollama:" + (localLlmModels.isEmpty() ? "llama3.1:8b" : localLlmModels.get(0));
        } else {
            defaultModel = DeepSeekClient.MODEL_REASONER;
        }
        clientManager.setCurrentModel(defaultModel);

        clientManager.initializeContextManager(contextManager, summaryAgent);
        clientManager.setMemoryService(memoryService);

        sessionService.loadLastSession();

        AppContext appContext = AppContext.getInstance();
        appContext.setClientManager(clientManager);
        appContext.setSessionService(sessionService);
        appContext.setSummaryAgent(summaryAgent);
        appContext.setStrategyFactory(strategyFactory);
        appContext.setGlobalSummaryRepository(globalSummaryRepository);
        appContext.setProfileRepository(profileRepository);
        appContext.setMemoryService(memoryService);
        appContext.setMemoryExtractionAgent(memoryExtractionAgent);
        appContext.setTaskService(taskService);
        appContext.setTaskManagerAgent(taskManagerAgent);
        appContext.setFactsRepository(factsRepository);
        appContext.setBranchRepository(branchRepository);
        appContext.setFactsExtractionAgent(factsExtractionAgent);
        appContext.setInvariantService(invariantService);

        McpService mcpService = new McpService();
        appContext.setMcpService(mcpService);

        McpSseProxyService mcpSseProxyService = new McpSseProxyService(mcpService);
        appContext.setMcpSseProxyService(mcpSseProxyService);
        
        SessionMcpRepository sessionMcpRepository = new SessionMcpRepository();
        
        McpToolIntegrationService mcpToolIntegrationService = new McpToolIntegrationService(mcpService, sessionMcpRepository);
        clientManager.setMcpToolIntegrationService(mcpToolIntegrationService);

        SessionHeartbeatRepository heartbeatRepository = new SessionHeartbeatRepository();
        appContext.setHeartbeatRepository(heartbeatRepository);

        TaskRecoveryService recoveryService = new TaskRecoveryService();
        var pausedTasks = recoveryService.findPausedTasks();
        if (!pausedTasks.isEmpty()) {
            log.info("Found {} paused tasks from previous session", pausedTasks.size());
            for (var task : pausedTasks) {
                log.info("  - Task {} (session {}): {} - {}", task.id(), task.sessionId(), task.title(), task.pauseReason());
            }
        }

        HeartbeatMonitor heartbeatMonitor = new HeartbeatMonitor(taskService);
        appContext.setHeartbeatMonitor(heartbeatMonitor);

        var activeTasks = recoveryService.findActiveTasks();
        if (!activeTasks.isEmpty()) {
            log.info("Starting HeartbeatMonitor ({} active tasks found)", activeTasks.size());
            heartbeatMonitor.start();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received, pausing active tasks...");
            try {
                mcpSseProxyService.shutdown();
                log.info("MCP SSE proxy service stopped");
            } catch (Exception e) {
                log.error("Error stopping MCP SSE proxy: {}", e.getMessage());
            }
            try {
                int paused = recoveryService.pauseAllActiveTasks("Server shutdown");
                log.info("Graceful shutdown: paused {} tasks", paused);
            } catch (Exception e) {
                log.error("Error during shutdown: {}", e.getMessage());
            }
        }, "shutdown-hook"));

        ChatController chatController = new ChatController(appContext);
        SettingsController settingsController = new SettingsController(appContext);
        SessionController sessionController = new SessionController(appContext);
        ContextController contextController = new ContextController(appContext);
        ProfileController profileController = new ProfileController(appContext);
        MemoryController memoryController = new MemoryController(appContext);
        TaskController taskController = new TaskController(appContext);
        ProviderController providerController = new ProviderController(appContext);
        McpController mcpController = new McpController(appContext);
        mcpController.setSessionMcpRepository(sessionMcpRepository);

        OllamaClient ollamaClient = new OllamaClient();
        ChunkMetadataRepository chunkMetadataRepository = new ChunkMetadataRepository(new DatabaseConfig());
        EmbeddingService embeddingService = new EmbeddingService(ollamaClient, chunkMetadataRepository);
        embeddingService.loadIndex();
        EmbeddingController embeddingController = new EmbeddingController(embeddingService);
        
        RagService ragService = new RagService(embeddingService, appContext);
        appContext.setRagService(ragService);
        
        RerankerClient rerankerClient = new RerankerClient();
        RerankerService rerankerService = new RerankerService(rerankerClient);
        ragService.setRerankerService(rerankerService);
        appContext.setRerankerService(rerankerService);
        
        autoIndexKnowledgeBase(embeddingService);

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

        app.post("/api/chat", chatController::handleChat);
        app.post("/api/clear", chatController::handleClear);
        app.get("/api/history", chatController::handleHistory);
        app.post("/api/limited", chatController::handleLimited);

        app.get("/api/tsm", settingsController::handleGetTsm);
        app.post("/api/tsm", settingsController::handleSetTsm);
        app.get("/api/model", settingsController::handleGetModel);
        app.post("/api/model", settingsController::handleSetModel);
        app.get("/api/system", settingsController::handleSystem);
        app.get("/api/settings", settingsController::handleGetSettings);
        app.post("/api/settings", settingsController::handleSetSettings);
        app.get("/api/thinking", settingsController::handleGetThinking);
        app.post("/api/thinking", settingsController::handleSetThinking);
        app.get("/api/rag", settingsController::handleGetRag);
        app.get("/api/rag/status", settingsController::handleGetRagStatus);
        app.post("/api/rag", settingsController::handleSetRag);
        app.get("/api/rag/strategy", settingsController::handleGetRagStrategy);
        app.post("/api/rag/strategy", settingsController::handleSetRagStrategy);
        app.post("/api/rag/reindex", settingsController::handleReindexRag);
        app.get("/api/rag/reranker", settingsController::handleGetReranker);
        app.post("/api/rag/reranker", settingsController::handleSetReranker);

        app.get("/api/sessions", sessionController::handleGetSessions);
        app.post("/api/sessions", sessionController::handleCreateSession);
        app.get("/api/sessions/active", sessionController::handleGetActiveSession);
        app.get("/api/sessions/{id}", sessionController::handleGetSession);
        app.delete("/api/sessions/{id}", sessionController::handleDeleteSession);
        app.get("/api/sessions/{id}/messages", sessionController::handleGetSessionMessages);
        app.post("/api/sessions/{id}/activate", sessionController::handleActivateSession);
        app.get("/api/sessions/{id}/stats", sessionController::handleGetSessionStats);

        app.get("/api/strategies", contextController::handleGetStrategies);
        app.get("/api/sessions/{id}/context-strategy", contextController::handleGetContextStrategy);
        app.post("/api/sessions/{id}/context-strategy", contextController::handleSetContextStrategy);
        app.get("/api/sessions/{id}/branches", contextController::handleGetBranches);
        app.post("/api/sessions/{id}/branches", contextController::handleCreateBranch);
        app.post("/api/sessions/{id}/branches/{branchId}/switch", contextController::handleSwitchBranch);
        app.delete("/api/sessions/{id}/branches/{branchId}", contextController::handleDeleteBranch);
        app.get("/api/sessions/{id}/branches/{branchId}/stats", contextController::handleGetBranchStats);
        app.get("/api/sessions/{id}/compression-settings", contextController::handleGetCompressionSettings);
        app.post("/api/sessions/{id}/compression-settings", contextController::handleSetCompressionSettings);
        app.get("/api/sessions/{id}/sliding-window-settings", contextController::handleGetSlidingWindowSettings);
        app.post("/api/sessions/{id}/sliding-window-settings", contextController::handleSetSlidingWindowSettings);
        app.get("/api/sessions/{id}/sticky-facts-settings", contextController::handleGetStickyFactsSettings);
        app.post("/api/sessions/{id}/sticky-facts-settings", contextController::handleSetStickyFactsSettings);
        app.get("/api/sessions/{id}/facts", contextController::handleGetFacts);
        app.post("/api/sessions/{id}/facts", contextController::handleSaveFact);
        app.put("/api/sessions/{id}/facts/{factId}", contextController::handleUpdateFact);
        app.delete("/api/sessions/{id}/facts/{factId}", contextController::handleDeleteFact);
        app.post("/api/sessions/{id}/facts/extract", contextController::handleExtractFacts);

        app.get("/api/profiles", profileController::handleGetProfiles);
        app.post("/api/profiles", profileController::handleCreateProfile);
        app.get("/api/profiles/{id}", profileController::handleGetProfile);
        app.put("/api/profiles/{id}", profileController::handleUpdateProfile);
        app.delete("/api/profiles/{id}", profileController::handleDeleteProfile);
        app.get("/api/profiles/default", profileController::handleGetDefaultProfile);
        app.post("/api/sessions/{id}/set-profile/{profileId}", profileController::handleSetSessionProfile);

        app.get("/api/sessions/{id}/memory/working", memoryController::handleGetWorkingMemory);
        app.post("/api/sessions/{id}/memory/working", memoryController::handleSaveWorkingMemory);
        app.put("/api/sessions/{id}/memory/working/{key}", memoryController::handleUpdateWorkingMemory);
        app.delete("/api/sessions/{id}/memory/working/{key}", memoryController::handleDeleteWorkingMemory);
        app.get("/api/profiles/{id}/memory/longterm", memoryController::handleGetLongTermMemory);
        app.post("/api/profiles/{id}/memory/longterm", memoryController::handleSaveLongTermMemory);
        app.put("/api/profiles/{id}/memory/longterm/{key}", memoryController::handleUpdateLongTermMemory);
        app.delete("/api/profiles/{id}/memory/longterm/{key}", memoryController::handleDeleteLongTermMemory);
        app.post("/api/sessions/{id}/memory/suggest", memoryController::handleSuggestMemory);
        app.get("/api/sessions/{id}/memory/suggestions", memoryController::handleGetMemorySuggestions);
        app.post("/api/memory/analyze", memoryController::handleAnalyzeText);
        app.post("/api/sessions/{id}/memory/suggestions/viewed", memoryController::handleMarkSuggestionsViewed);

        app.get("/api/sessions/{id}/tasks", taskController::handleGetTasks);
        app.post("/api/sessions/{id}/tasks", taskController::handleCreateTask);
        app.get("/api/sessions/{id}/tasks/{taskId}", taskController::handleGetTask);
        app.put("/api/sessions/{id}/tasks/{taskId}", taskController::handleUpdateTask);
        app.delete("/api/sessions/{id}/tasks/{taskId}", taskController::handleDeleteTask);
        app.post("/api/sessions/{id}/tasks/{taskId}/transition", taskController::handleTransitionTask);
        app.post("/api/sessions/{id}/tasks/{taskId}/pause", taskController::handlePauseTask);
        app.post("/api/sessions/{id}/tasks/{taskId}/resume", taskController::handleResumeTask);
        app.post("/api/sessions/{id}/tasks/create-with-plan", taskController::handleCreateTaskWithPlan);
        app.get("/api/sessions/{id}/tasks/{taskId}/context", taskController::handleGetTaskContext);
        app.post("/api/sessions/{id}/tasks/{taskId}/increment-step", taskController::handleIncrementStep);
        app.post("/api/sessions/{id}/tasks/{taskId}/add-done", taskController::handleAddDone);
        app.post("/api/sessions/{id}/tasks/{taskId}/update-current", taskController::handleUpdateCurrent);
        app.post("/api/sessions/{id}/tasks/{taskId}/validate-and-transition", taskController::handleValidateAndTransition);
        app.post("/api/sessions/{id}/tasks/{taskId}/confirm-plan", taskController::handleConfirmPlan);
        app.post("/api/sessions/{id}/tasks/{taskId}/replan", taskController::handleReplanTask);
        app.get("/api/sessions/{id}/active-task", taskController::handleGetActiveTask);
        app.get("/api/sessions/{id}/tasks/{taskId}/messages", taskController::handleGetTaskMessages);
        app.get("/api/sessions/{id}/tasks/{taskId}/messages/{state}", taskController::handleGetTaskMessagesByState);

        app.post("/api/heartbeat", taskController::handleHeartbeat);
        app.get("/api/paused-tasks", taskController::handleGetPausedTasks);

        app.get("/api/providers", providerController::handleGetProviders);
        app.get("/api/models", providerController::handleGetModels);
        app.get("/api/info", providerController::handleInfo);

        app.get("/api/mcp/servers", mcpController::handleGetServers);
        app.post("/api/mcp/servers/{name}/connect", mcpController::handleConnect);
        app.post("/api/mcp/servers/{name}/disconnect", mcpController::handleDisconnect);
        app.get("/api/mcp/servers/{name}/tools", mcpController::handleGetTools);
        app.post("/api/mcp/servers/{name}/tools/{tool}", mcpController::handleCallTool);
        app.get("/api/mcp/servers/{name}/status", mcpController::handleGetStatus);
        app.sse("/api/mcp/servers/{name}/stream", mcpController::handleStream);
        app.get("/api/mcp/tools", mcpController::handleGetAllTools);
        app.post("/api/mcp/reload", mcpController::handleReloadConfig);
        app.get("/api/sessions/{sessionId}/mcp-servers", mcpController::handleGetSessionServers);
        app.post("/api/sessions/{sessionId}/mcp-servers/{serverName}", mcpController::handleSetSessionServer);

        app.post("/api/embeddings/index", embeddingController::indexFile);
        app.get("/api/embeddings/search", embeddingController::search);
        app.delete("/api/embeddings/index", embeddingController::clearIndex);
        app.delete("/api/embeddings/index/{source}", embeddingController::removeSource);
        app.get("/api/embeddings/stats", embeddingController::getStats);
        app.get("/api/embeddings/compare", embeddingController::compareStrategies);
        app.get("/api/embeddings/ollama", embeddingController::checkOllama);

        app.start(port);

        log.info("Server started on port {}", port);
        log.info("╔══════════════════════════════════════════════════════════╗");
        log.info("║           AI Chat Interface - Запущен!                   ║");
        log.info("╠══════════════════════════════════════════════════════════╣");
        log.info("║  Откройте в браузере: http://localhost:{}              ║", port);
        log.info("║  Нажмите Ctrl+C для остановки сервера                    ║");
        log.info("╚══════════════════════════════════════════════════════════╝");

        openBrowser("http://localhost:" + port);
    }

    private static void autoIndexKnowledgeBase(EmbeddingService embeddingService) {
        try {
            if (embeddingService.getStats().totalVectorCount() > 0) {
                log.info("RAG knowledge base already indexed: {} vectors", embeddingService.getStats().totalVectorCount());
                return;
            }
            
            String knowledgePath = "src/main/resources/static/text";
            java.io.File knowledgeDir = new java.io.File(knowledgePath);
            
            if (!knowledgeDir.exists() || !knowledgeDir.isDirectory()) {
                log.info("RAG knowledge folder not found: {}", knowledgePath);
                return;
            }
            
            if (!embeddingService.isOllamaAvailable() || !embeddingService.hasOllamaModel()) {
                log.info("Ollama not available, skipping RAG auto-indexing");
                return;
            }
            
            log.info("Auto-indexing RAG knowledge base: {}", knowledgePath);
            
            log.info("Indexing with FIXED strategy...");
            List<EmbeddingService.IndexResult> fixedResults = embeddingService.indexDirectory(knowledgePath, "FIXED", true);
            log.info("FIXED: {} files, {} chunks", fixedResults.size(), fixedResults.stream().mapToInt(r -> r.chunkCount()).sum());
            
            log.info("Indexing with STRUCTURE strategy...");
            List<EmbeddingService.IndexResult> structureResults = embeddingService.indexDirectory(knowledgePath, "STRUCTURE", true);
            log.info("STRUCTURE: {} files, {} chunks", structureResults.size(), structureResults.stream().mapToInt(r -> r.chunkCount()).sum());
            
            int totalFiles = fixedResults.size() + structureResults.size();
            int totalChunks = fixedResults.stream().mapToInt(r -> r.chunkCount()).sum() 
                            + structureResults.stream().mapToInt(r -> r.chunkCount()).sum();
            log.info("Auto-indexing complete: {} files, {} total chunks", totalFiles, totalChunks);
        } catch (Exception e) {
            log.warn("Auto-indexing failed: {}", e.getMessage());
        }
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
}
