package com.example.deepseek.app.controllers;

import com.example.deepseek.client.AiClient;
import com.example.deepseek.client.ClientManager;
import com.example.deepseek.client.PricingService;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class SettingsController {
    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);
    
    private final AppContext ctx;
    
    public SettingsController(AppContext ctx) {
        this.ctx = ctx;
    }
    
    public void handleGetMode(Context ctx) {
        log.info("Get mode: current_mode={}", this.ctx.getCurrentMode());
        ctx.json(Map.of(
            "mode", this.ctx.getCurrentMode(),
            "modeName", this.ctx.getCurrentMode() == 1 ? "Тестировщик" : "Помощник"
        ));
    }

    public void handleSetMode(Context ctx) throws Exception {
        Map<String, Integer> request = ctx.bodyAsClass(Map.class);
        Integer mode = request.get("mode");

        log.info("Set mode: old_mode={}, new_mode={}", this.ctx.getCurrentMode(), mode);

        if (mode == null || (mode != 1 && mode != 2)) {
            ctx.status(400).json(Map.of("success", false, "error", "Режим должен быть 1 (Tester) или 2 (Helper)"));
            return;
        }

        this.ctx.setCurrentMode(mode);
        this.ctx.getClientManager().setMode(mode);
        this.ctx.getClientManager().clearAllHistory();

        long oldSessionId = this.ctx.getSessionService().getCurrentSessionId();
        long profileId = this.ctx.getProfileIdForSession(oldSessionId);

        long newSessionId = this.ctx.getSessionService().createSession(
            "Новая сессия",
            this.ctx.getClientManager().getCurrentModel(),
            this.ctx.getClientManager().getSystemMessage(),
            this.ctx.getCurrentMode(),
            profileId
        );

        ctx.json(Map.of(
            "success", true,
            "mode", this.ctx.getCurrentMode(),
            "modeName", this.ctx.getCurrentMode() == 1 ? "Тестировщик" : "Помощник",
            "message", "Режим изменён, создана новая сессия"
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
            "systemPrompt", systemMessage != null ? systemMessage : "",
            "modeDescription", this.ctx.getCurrentMode() == 1 ? "Тестировщик" : "Помощник"
        ));
    }

    public void handleGetSettings(Context ctx) {
        log.info("Get settings");
        Map<String, Object> settings = new HashMap<>();
        settings.put("mode", this.ctx.getCurrentMode());
        settings.put("modeDescription", this.ctx.getCurrentMode() == 1 ? "Тестировщик" : "Помощник");
        settings.put("maxTokens", this.ctx.getClientManager().getCurrentClient().getMaxTokens());
        settings.put("maxTokensEnabled", this.ctx.getClientManager().getCurrentClient().isMaxTokensEnabled());
        settings.put("temperature", this.ctx.getClientManager().getCurrentClient().getTemperature());
        settings.put("temperatureEnabled", this.ctx.getClientManager().getCurrentClient().isTemperatureEnabled());
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
}
