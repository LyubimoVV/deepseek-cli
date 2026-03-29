package com.example.deepseek.app.controllers;

import com.example.deepseek.client.ClientManager;
import com.example.deepseek.client.DeepSeekClient;
import com.example.deepseek.client.OllamaChatClient;
import com.example.deepseek.client.OpenRouterClient;
import com.example.deepseek.client.PricingService;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ProviderController {
    private static final Logger log = LoggerFactory.getLogger(ProviderController.class);
    
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

        List<String> ollamaModels = OllamaChatClient.getAvailableModels();
        if (!ollamaModels.isEmpty()) {
            List<String> ollamaModelIds = ollamaModels.stream()
                .map(m -> "ollama:" + m)
                .toList();
            Map<String, Object> ollama = new HashMap<>();
            ollama.put("name", "Ollama");
            ollama.put("displayName", "Ollama (Local)");
            ollama.put("models", ollamaModelIds);
            ollama.put("isLocal", true);
            providers.add(ollama);
        }

        ctx.json(Map.of("success", true, "providers", providers));
    }

    public void handleGetModels(Context ctx) {
        log.info("Get models");
        List<Map<String, Object>> models = new ArrayList<>();

        for (String model : this.ctx.getClientManager().getAvailableModels()) {
            Map<String, Object> modelInfo = new HashMap<>();
            modelInfo.put("id", model);
            modelInfo.put("displayName", PricingService.getModelDisplayName(model));
            modelInfo.put("provider", PricingService.getProviderName(model));
            modelInfo.put("pricePerMillion", PricingService.getFormattedCost(model));
            modelInfo.put("isFree", PricingService.isOpenRouterModel(model) || model.endsWith(":free") || PricingService.isOllamaModel(model));
            modelInfo.put("isLocal", PricingService.isOllamaModel(model));
            models.add(modelInfo);
        }

        List<String> ollamaModels = OllamaChatClient.getAvailableModels();
        for (String ollamaModel : ollamaModels) {
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

        ctx.json(Map.of("success", true, "models", models));
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
