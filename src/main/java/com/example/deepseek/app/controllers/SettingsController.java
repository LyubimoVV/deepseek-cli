package com.example.deepseek.app.controllers;

import com.example.deepseek.client.AiClient;
import com.example.deepseek.client.ClientManager;
import com.example.deepseek.client.PricingService;
import com.example.deepseek.embedding.EmbeddingService;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class SettingsController {
    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);
    
    private final AppContext ctx;
    
    public SettingsController(AppContext ctx) {
        this.ctx = ctx;
    }
    
    public void handleGetTsm(Context ctx) {
        log.info("Get TSM: enabled={}", this.ctx.isTsmEnabled());
        ctx.json(Map.of(
            "success", true,
            "tsmEnabled", this.ctx.isTsmEnabled()
        ));
    }

    public void handleSetTsm(Context ctx) throws Exception {
        Map<String, Boolean> request = ctx.bodyAsClass(Map.class);
        Boolean enabled = request.get("enabled");

        log.info("Set TSM: old_enabled={}, new_enabled={}", this.ctx.isTsmEnabled(), enabled);

        if (enabled == null) {
            ctx.status(400).json(Map.of("success", false, "error", "Параметр 'enabled' обязателен"));
            return;
        }

        this.ctx.setTsmEnabled(enabled);

        ctx.json(Map.of(
            "success", true,
            "tsmEnabled", this.ctx.isTsmEnabled(),
            "message", enabled ? "Task State Machine включена" : "Task State Machine выключена"
        ));
    }

    public void handleGetModel(Context ctx) {
        String model = this.ctx.getClientManager().getCurrentModel();
        log.info("Get model: current_model={}", model);
        ctx.json(Map.of(
            "model", model,
            "modelName", PricingService.getModelDisplayName(model),
            "provider", PricingService.getProviderName(model)
        ));
    }

    public void handleSetModel(Context ctx) throws Exception {
        Map<String, String> request = ctx.bodyAsClass(Map.class);
        String newModel = request.get("model");
        String oldModel = this.ctx.getClientManager().getCurrentModel();

        log.info("Set model: old_model={}, new_model={}", oldModel, newModel);

        if (newModel == null || !this.ctx.getClientManager().hasClient(newModel)) {
            String errorMsg = "Модель не найдена или недоступна: " + newModel;
            if (newModel != null && newModel.contains("/")) {
                errorMsg += ". Установите переменную окружения OPENROUTER_API_KEY";
            }
            ctx.status(400).json(Map.of("success", false, "error", errorMsg));
            return;
        }

        this.ctx.getClientManager().setCurrentModel(newModel);
        this.ctx.getSessionService().updateSessionModel(newModel);

        log.info("Set model: success, new_model={}", newModel);
        ctx.json(Map.of(
            "success", true,
            "model", newModel,
            "modelName", PricingService.getModelDisplayName(newModel),
            "provider", PricingService.getProviderName(newModel),
            "message", "Модель изменена на " + PricingService.getModelDisplayName(newModel)
        ));
    }

    public void handleSystem(Context ctx) {
        log.info("Get system prompt");
        String systemMessage = this.ctx.getClientManager().getSystemMessage();
        ctx.json(Map.of(
            "success", true,
            "systemPrompt", systemMessage != null ? systemMessage : ""
        ));
    }

    public void handleGetSettings(Context ctx) {
        log.info("Get settings");
        Map<String, Object> settings = new HashMap<>();
        settings.put("tsmEnabled", this.ctx.isTsmEnabled());
        settings.put("maxTokens", this.ctx.getClientManager().getCurrentClient().getMaxTokens());
        settings.put("maxTokensEnabled", this.ctx.getClientManager().getCurrentClient().isMaxTokensEnabled());
        settings.put("temperature", this.ctx.getClientManager().getCurrentClient().getTemperature());
        settings.put("temperatureEnabled", this.ctx.getClientManager().getCurrentClient().isTemperatureEnabled());
        settings.put("stopSequences", this.ctx.getClientManager().getCurrentClient().getStopSequences());
        settings.put("stopSequencesEnabled", this.ctx.getClientManager().getCurrentClient().isStopSequencesEnabled());
        settings.put("systemPrompt", this.ctx.getClientManager().getSystemMessage());
        settings.put("model", this.ctx.getClientManager().getCurrentModel());
        settings.put("availableModels", this.ctx.getClientManager().getAvailableModels());

        ctx.json(Map.of("success", true, "settings", settings));
    }

    public void handleSetSettings(Context ctx) throws Exception {
        Map<String, Object> request = ctx.bodyAsClass(Map.class);
        AiClient client = this.ctx.getClientManager().getCurrentClient();
        String param = (String) request.get("param");

        log.info("Set settings: param={}", param);

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
                    
                case "stop_sequences":
                    String stopSequencesStr = (String) request.get("value");
                    if (stopSequencesStr != null) {
                        List<String> sequences = Arrays.stream(stopSequencesStr.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
                        client.setStopSequences(sequences);
                        ctx.json(Map.of("success", true, "message", "Стоп-последовательности обновлены"));
                        return;
                    }
                    break;
                    
                case "stop_sequences_enabled":
                    Boolean stopSequencesEnabled = (Boolean) request.get("value");
                    if (stopSequencesEnabled != null) {
                        client.setStopSequencesEnabled(stopSequencesEnabled);
                        ctx.json(Map.of("success", true, "message", 
                            stopSequencesEnabled ? "Стоп-последовательности включены" : "Стоп-последовательности выключены"));
                        return;
                    }
                    break;
                    
                case "system_prompt":
                    String systemPrompt = (String) request.get("value");
                    if (systemPrompt != null && !systemPrompt.isBlank()) {
                        this.ctx.getClientManager().setSystemMessage(systemPrompt);
                        ctx.json(Map.of("success", true, "message", "Системный промпт обновлён"));
                        return;
                    }
                    break;
            }
        }

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

    public void handleGetThinking(Context ctx) {
        log.info("Get thinking");
        ctx.json(Map.of(
            "success", true,
            "thinkingEnabled", this.ctx.getClientManager().isThinkingEnabled(),
            "supportsThinking", this.ctx.getClientManager().supportsThinking(),
            "currentModel", this.ctx.getClientManager().getCurrentModel()
        ));
    }

    public void handleSetThinking(Context ctx) throws Exception {
        Map<String, Boolean> request = ctx.bodyAsClass(Map.class);
        Boolean enabled = request.get("enabled");

        log.info("Set thinking: requested_enabled={}", enabled);

        if (enabled == null) {
            ctx.status(400).json(Map.of("success", false, "error", "Параметр 'enabled' обязателен"));
            return;
        }

        if (!this.ctx.getClientManager().supportsThinking()) {
            ctx.status(400).json(Map.of("success", false, "error", "Thinking mode поддерживается только для DeepSeek Reasoner"));
            return;
        }

        this.ctx.getClientManager().setThinkingEnabled(enabled);
        ctx.json(Map.of(
            "success", true,
            "thinkingEnabled", enabled,
            "message", enabled ? "Thinking mode включён" : "Thinking mode выключен"
        ));
    }

    public void handleGetRag(Context ctx) {
        log.info("Get RAG: enabled={}", this.ctx.isRagEnabled());
        
        boolean available = false;
        int chunksCount = 0;
        
        if (this.ctx.getRagService() != null) {
            available = this.ctx.getRagService().isAvailable();
            chunksCount = this.ctx.getRagService().getChunksCount();
        }
        
        ctx.json(Map.of(
            "success", true,
            "ragEnabled", this.ctx.isRagEnabled(),
            "ragAvailable", available,
            "chunksCount", chunksCount
        ));
    }

    public void handleSetRag(Context ctx) throws Exception {
        Map<String, Boolean> request = ctx.bodyAsClass(Map.class);
        Boolean enabled = request.get("enabled");

        log.info("Set RAG: old_enabled={}, new_enabled={}", this.ctx.isRagEnabled(), enabled);

        if (enabled == null) {
            ctx.status(400).json(Map.of("success", false, "error", "Параметр 'enabled' обязателен"));
            return;
        }

        if (enabled && this.ctx.getRagService() != null && !this.ctx.getRagService().isAvailable()) {
            ctx.status(400).json(Map.of(
                "success", false, 
                "error", "RAG недоступен. Убедитесь, что Ollama запущена и модель nomic-embed-text установлена."
            ));
            return;
        }

        this.ctx.setRagEnabled(enabled);

        ctx.json(Map.of(
            "success", true,
            "ragEnabled", this.ctx.isRagEnabled(),
            "message", enabled ? "RAG включён" : "RAG выключен"
        ));
    }

    public void handleGetRagStrategy(Context ctx) {
        ctx.json(Map.of(
            "success", true,
            "strategy", this.ctx.getRagSearchStrategy()
        ));
    }

    public void handleSetRagStrategy(Context ctx) throws Exception {
        Map<String, String> request = ctx.bodyAsClass(Map.class);
        String strategy = request.get("strategy");

        log.info("Set RAG strategy: old={}, new={}", this.ctx.getRagSearchStrategy(), strategy);

        if (strategy == null || strategy.isBlank()) {
            ctx.status(400).json(Map.of("success", false, "error", "Параметр 'strategy' обязателен"));
            return;
        }

        if (!strategy.equals("FIXED") && !strategy.equals("STRUCTURE") && !strategy.equals("BOTH")) {
            ctx.status(400).json(Map.of("success", false, "error", "Стратегия должна быть: FIXED, STRUCTURE или BOTH"));
            return;
        }

        this.ctx.setRagSearchStrategy(strategy);

        ctx.json(Map.of(
            "success", true,
            "strategy", this.ctx.getRagSearchStrategy(),
            "message", "Стратегия поиска RAG: " + strategy
        ));
    }

    public void handleReindexRag(Context ctx) {
        log.info("RAG reindex requested");
        
        if (this.ctx.getRagService() == null) {
            ctx.status(400).json(Map.of("success", false, "error", "RAG service not available"));
            return;
        }
        
        try {
            String knowledgePath = "src/main/resources/static/text";
            java.io.File knowledgeDir = new java.io.File(knowledgePath);
            
            if (!knowledgeDir.exists() || !knowledgeDir.isDirectory()) {
                ctx.status(400).json(Map.of("success", false, "error", "Knowledge folder not found: " + knowledgePath));
                return;
            }
            
            EmbeddingService embeddingService = this.ctx.getRagService().getEmbeddingService();
            if (embeddingService == null) {
                ctx.status(400).json(Map.of("success", false, "error", "Embedding service not available"));
                return;
            }
            
            log.info("Clearing existing RAG index...");
            embeddingService.clearIndex(null);
            
            log.info("Reindexing with FIXED strategy...");
            List<EmbeddingService.IndexResult> fixedResults = embeddingService.indexDirectory(knowledgePath, "FIXED", true);
            int fixedChunks = fixedResults.stream().mapToInt(r -> r.chunkCount()).sum();
            log.info("FIXED: {} files, {} chunks", fixedResults.size(), fixedChunks);
            
            log.info("Reindexing with STRUCTURE strategy...");
            List<EmbeddingService.IndexResult> structureResults = embeddingService.indexDirectory(knowledgePath, "STRUCTURE", true);
            int structureChunks = structureResults.stream().mapToInt(r -> r.chunkCount()).sum();
            log.info("STRUCTURE: {} files, {} chunks", structureResults.size(), structureChunks);
            
            int totalChunks = fixedChunks + structureChunks;
            log.info("Reindex complete: {} total chunks", totalChunks);
            
            ctx.json(Map.of(
                "success", true,
                "fixedChunks", fixedChunks,
                "structureChunks", structureChunks,
                "totalChunks", totalChunks,
                "message", "Переиндексация завершена: " + totalChunks + " чанков"
            ));
        } catch (Exception e) {
            log.error("Reindex failed: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("success", false, "error", "Ошибка переиндексации: " + e.getMessage()));
        }
    }

    public void handleGetReranker(Context ctx) {
        log.info("Get Reranker settings");
        
        boolean available = false;
        String modelName = null;
        
        if (this.ctx.getRerankerService() != null) {
            available = this.ctx.getRerankerService().isAvailable();
            modelName = this.ctx.getRerankerService().getModelName();
        }
        
        ctx.json(Map.of(
            "success", true,
            "rerankerEnabled", this.ctx.isRerankerEnabled(),
            "rerankerAvailable", available,
            "rerankerModel", modelName,
            "threshold", this.ctx.getRerankerThreshold(),
            "topKBefore", this.ctx.getRerankerTopKBefore(),
            "topKAfter", this.ctx.getRerankerTopKAfter()
        ));
    }

    public void handleSetReranker(Context ctx) throws Exception {
        Map<String, Object> request = ctx.bodyAsClass(Map.class);
        
        if (request.containsKey("enabled")) {
            Boolean enabled = (Boolean) request.get("enabled");
            log.info("Set Reranker enabled: {}", enabled);
            
            if (enabled && this.ctx.getRerankerService() != null && !this.ctx.getRerankerService().isAvailable()) {
                ctx.status(400).json(Map.of(
                    "success", false,
                    "error", "Reranker недоступен. Убедитесь, что Ollama запущена и модель bge-reranker-v2-m3 установлена."
                ));
                return;
            }
            
            this.ctx.setRerankerEnabled(enabled);
        }
        
        if (request.containsKey("threshold")) {
            Object thresholdObj = request.get("threshold");
            double threshold = thresholdObj instanceof Number ? ((Number) thresholdObj).doubleValue() : 0.5;
            if (threshold < 0) threshold = 0;
            if (threshold > 1) threshold = 1;
            log.info("Set Reranker threshold: {}", threshold);
            this.ctx.setRerankerThreshold(threshold);
        }
        
        if (request.containsKey("topKBefore")) {
            Object topKBeforeObj = request.get("topKBefore");
            int topKBefore = topKBeforeObj instanceof Number ? ((Number) topKBeforeObj).intValue() : 20;
            if (topKBefore < 1) topKBefore = 1;
            if (topKBefore > 100) topKBefore = 100;
            log.info("Set Reranker topKBefore: {}", topKBefore);
            this.ctx.setRerankerTopKBefore(topKBefore);
        }
        
        if (request.containsKey("topKAfter")) {
            Object topKAfterObj = request.get("topKAfter");
            int topKAfter = topKAfterObj instanceof Number ? ((Number) topKAfterObj).intValue() : 5;
            if (topKAfter < 1) topKAfter = 1;
            if (topKAfter > 50) topKAfter = 50;
            log.info("Set Reranker topKAfter: {}", topKAfter);
            this.ctx.setRerankerTopKAfter(topKAfter);
        }
        
        ctx.json(Map.of(
            "success", true,
            "rerankerEnabled", this.ctx.isRerankerEnabled(),
            "threshold", this.ctx.getRerankerThreshold(),
            "topKBefore", this.ctx.getRerankerTopKBefore(),
            "topKAfter", this.ctx.getRerankerTopKAfter(),
            "message", "Настройки реранкинга обновлены"
        ));
    }
}
