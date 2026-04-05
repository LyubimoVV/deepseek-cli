package com.example.deepseek.app.controllers;

import com.example.deepseek.dto.MemoryRequest;
import com.example.deepseek.memory.MemoryLayer;
import com.example.deepseek.memory.MemoryScope;
import com.example.deepseek.memory.MemoryService;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class MemoryController {
    private static final Logger log = LoggerFactory.getLogger(MemoryController.class);
    
    private final AppContext ctx;
    
    public MemoryController(AppContext ctx) {
        this.ctx = ctx;
    }
    
    private void validateMemoryKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new IllegalArgumentException("Key must be 1-100 characters");
        }
        if (!key.matches("^[a-zA-Z0-9_\\-\\.]+$")) {
            throw new IllegalArgumentException("Key contains invalid characters");
        }
    }

    private void validateMemoryValue(String value) {
        if (value == null || value.length() > 10_000) {
            throw new IllegalArgumentException("Value must be 1-10,000 characters");
        }
    }

    public void handleGetWorkingMemory(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            var memory = this.ctx.getMemoryService().getWorkingMemory(sessionId);
            ctx.json(Map.of("success", true, "memory", memory));
        } catch (Exception e) {
            log.error("Error getting working memory: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleSaveWorkingMemory(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            var request = ctx.bodyAsClass(MemoryRequest.class);
            validateMemoryKey(request.key());
            validateMemoryValue(request.value());
            var scope = MemoryScope.ofSession(sessionId);
            this.ctx.getMemoryService().save(scope, request.category(), request.key(), request.value(), MemoryLayer.WORKING);
            ctx.json(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error saving working memory: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleUpdateWorkingMemory(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            String key = ctx.pathParam("key");
            var request = ctx.bodyAsClass(MemoryRequest.class);
            validateMemoryKey(key);
            validateMemoryValue(request.value());
            var scope = MemoryScope.ofSession(sessionId);
            this.ctx.getMemoryService().save(scope, request.category(), key, request.value(), MemoryLayer.WORKING);
            ctx.json(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating working memory: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleDeleteWorkingMemory(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            String key = ctx.pathParam("key");
            var scope = MemoryScope.ofSession(sessionId);
            this.ctx.getMemoryService().deleteFromMemory(scope, key, MemoryLayer.WORKING);
            ctx.json(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error deleting working memory: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleGetLongTermMemory(Context ctx) {
        try {
            long profileId = Long.parseLong(ctx.pathParam("id"));
            var memory = this.ctx.getMemoryService().getLongTermMemory(profileId);
            ctx.json(Map.of("success", true, "memory", memory));
        } catch (Exception e) {
            log.error("Error getting long term memory: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleSaveLongTermMemory(Context ctx) {
        try {
            long profileId = Long.parseLong(ctx.pathParam("id"));
            var request = ctx.bodyAsClass(MemoryRequest.class);
            validateMemoryKey(request.key());
            validateMemoryValue(request.value());
            var scope = MemoryScope.ofProfile(profileId);
            this.ctx.getMemoryService().save(scope, request.category(), request.key(), request.value(), MemoryLayer.LONG_TERM);
            ctx.json(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error saving long term memory: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleUpdateLongTermMemory(Context ctx) {
        try {
            long profileId = Long.parseLong(ctx.pathParam("id"));
            String key = ctx.pathParam("key");
            var request = ctx.bodyAsClass(MemoryRequest.class);
            validateMemoryKey(key);
            validateMemoryValue(request.value());
            var scope = MemoryScope.ofProfile(profileId);
            this.ctx.getMemoryService().save(scope, request.category(), key, request.value(), MemoryLayer.LONG_TERM);
            ctx.json(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating long term memory: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleDeleteLongTermMemory(Context ctx) {
        try {
            long profileId = Long.parseLong(ctx.pathParam("id"));
            String key = ctx.pathParam("key");
            var scope = MemoryScope.ofProfile(profileId);
            this.ctx.getMemoryService().deleteFromMemory(scope, key, MemoryLayer.LONG_TERM);
            ctx.json(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error deleting long term memory: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleSuggestMemory(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            this.ctx.getSessionService().analyzeSessionForSuggestions(sessionId);
            ctx.json(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error suggesting memory: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleGetMemorySuggestions(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            var suggestions = this.ctx.getSessionService().getSuggestions(sessionId);
            ctx.json(Map.of("success", true, "suggestions", suggestions));
        } catch (Exception e) {
            log.error("Error getting memory suggestions: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleAnalyzeText(Context ctx) {
        try {
            var request = ctx.bodyAsClass(Map.class);
            String content = (String) request.get("content");
            var scope = new MemoryScope(null, null);
            var suggestions = this.ctx.getMemoryExtractionAgent().analyze(content, scope);
            ctx.json(Map.of("success", true, "suggestions", suggestions));
        } catch (Exception e) {
            log.error("Error analyzing text: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleMarkSuggestionsViewed(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            this.ctx.getSessionService().markSuggestionsAsViewed(sessionId);
            ctx.json(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error marking suggestions as viewed: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }
}
