package com.example.deepseek.app.controllers;

import com.example.deepseek.client.ClientManager;
import com.example.deepseek.client.DeepSeekClient;
import com.example.deepseek.client.LocalLlmClient;
import com.example.deepseek.client.OllamaChatClient;
import com.example.deepseek.client.OpenRouterClient;
import com.example.deepseek.client.PricingService;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ProviderController {
    private static final Logger log = LoggerFactory.getLogger(ProviderController.class);
    private static final String PREFERRED_OLLAMA_MODEL = "llama3.1:8b";
    
    private final AppContext ctx;
    
    public ProviderController(AppContext ctx) {
        this.ctx = ctx;
    }
    
    public void handleGetProviders(Context ctx) {
        log.info("Get providers");
        List<Map<String, Object>> providers = new ArrayList<>();

        boolean hasDeepSeek = false;
        boolean hasOpenRouter = false;

        for (String model : this.ctx.getClientManager().getAvailableModels()) {
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

        String ollamaModel = getPreferredOllamaModel();
        if (ollamaModel != null) {
            Map<String, Object> ollama = new HashMap<>();
            ollama.put("name", "Ollama");
            ollama.put("displayName", "Ollama (Local)");
            ollama.put("models", List.of("ollama:" + ollamaModel));
            ollama.put("isLocal", true);
            providers.add(ollama);
        }

        String freeOllamaModel = getPreferredFreeOllamaModel();
        if (freeOllamaModel != null) {
            Map<String, Object> freeOllama = new HashMap<>();
            freeOllama.put("name", "FreeOllama");
            freeOllama.put("displayName", "Ollama (Free)");
            freeOllama.put("models", List.of("free_ollama:" + freeOllamaModel));
            freeOllama.put("isLocal", true);
            providers.add(freeOllama);
        }

        ctx.json(Map.of("success", true, "providers", providers));
    }

    public void handleGetModels(Context ctx) {
        log.info("Get models");
        List<Map<String, Object>> models = new ArrayList<>();

        for (String model : this.ctx.getClientManager().getAvailableModels()) {
            if (PricingService.isOllamaModel(model) && !model.equals("ollama:" + PREFERRED_OLLAMA_MODEL)) {
                continue;
            }
            if (PricingService.isFreeOllamaModel(model) && !model.equals("free_ollama:" + PREFERRED_OLLAMA_MODEL)) {
                continue;
            }
            Map<String, Object> modelInfo = new HashMap<>();
            modelInfo.put("id", model);
            modelInfo.put("displayName", PricingService.getModelDisplayName(model));
            modelInfo.put("provider", PricingService.getProviderName(model));
            modelInfo.put("pricePerMillion", PricingService.getFormattedCost(model));
            modelInfo.put("isFree", PricingService.isOpenRouterModel(model) || model.endsWith(":free") || PricingService.isOllamaModel(model) || PricingService.isFreeOllamaModel(model));
            modelInfo.put("isLocal", PricingService.isOllamaModel(model) || PricingService.isFreeOllamaModel(model));
            models.add(modelInfo);
        }

        String ollamaModel = getPreferredOllamaModel();
        if (ollamaModel != null) {
            String modelId = "ollama:" + ollamaModel;
            boolean alreadyRegistered = this.ctx.getClientManager().getAvailableModels().contains(modelId);
            if (!alreadyRegistered) {
                Map<String, Object> modelInfo = new HashMap<>();
                modelInfo.put("id", modelId);
                modelInfo.put("displayName", ollamaModel + " (Local)");
                modelInfo.put("provider", "Ollama");
                modelInfo.put("pricePerMillion", "FREE");
                modelInfo.put("isFree", true);
                modelInfo.put("isLocal", true);
                models.add(modelInfo);
            }
        }

        String freeOllamaModel = getPreferredFreeOllamaModel();
        if (freeOllamaModel != null) {
            String modelId = "free_ollama:" + freeOllamaModel;
            boolean alreadyRegistered = this.ctx.getClientManager().getAvailableModels().contains(modelId);
            if (!alreadyRegistered) {
                Map<String, Object> modelInfo = new HashMap<>();
                modelInfo.put("id", modelId);
                modelInfo.put("displayName", freeOllamaModel + " (Free)");
                modelInfo.put("provider", "FreeOllama");
                modelInfo.put("pricePerMillion", "FREE");
                modelInfo.put("isFree", true);
                modelInfo.put("isLocal", true);
                models.add(modelInfo);
            }
        }

        ctx.json(Map.of("success", true, "models", models));
    }

    private String getPreferredOllamaModel() {
        List<String> ollamaModels = OllamaChatClient.getAvailableModels();
        if (ollamaModels.isEmpty()) {
            return null;
        }
        if (ollamaModels.contains(PREFERRED_OLLAMA_MODEL)) {
            return PREFERRED_OLLAMA_MODEL;
        }
        return ollamaModels.stream()
            .filter(m -> m.startsWith("llama") || m.startsWith("qwen") || m.startsWith("mistral"))
            .findFirst()
            .orElse(ollamaModels.get(0));
    }

    private String getPreferredFreeOllamaModel() {
        List<String> freeOllamaModels = LocalLlmClient.getAvailableModels();
        if (freeOllamaModels.isEmpty()) {
            return null;
        }
        if (freeOllamaModels.contains(PREFERRED_OLLAMA_MODEL)) {
            return PREFERRED_OLLAMA_MODEL;
        }
        return freeOllamaModels.stream()
            .filter(m -> m.startsWith("llama") || m.startsWith("qwen") || m.startsWith("mistral"))
            .findFirst()
            .orElse(freeOllamaModels.get(0));
    }

    public void handleInfo(Context ctx) {
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
}
