package com.example.deepseek.app.controllers;

import com.example.deepseek.context.ContextStrategy;
import com.example.deepseek.db.BranchDto;
import com.example.deepseek.db.FactDto;
import com.example.deepseek.db.SessionService;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ContextController {
    private static final Logger log = LoggerFactory.getLogger(ContextController.class);
    
    private final AppContext ctx;
    
    public ContextController(AppContext ctx) {
        this.ctx = ctx;
    }
    
    public void handleGetStrategies(Context ctx) {
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

    private String getStrategyDescription(ContextStrategy strategy) {
        return switch (strategy) {
            case NONE -> "Без управления контекстом - полная история";
            case COMPRESSION -> "Суммаризация - автоматическое сжатие старых сообщений";
            case SLIDING_WINDOW -> "Скользящее окно - только последние N сообщений";
            case STICKY_FACTS -> "Sticky Facts - ключевые факты + последние N сообщений";
            case BRANCHING -> "Ветки диалога - создание альтернативных веток от чекпоинта";
        };
    }

    public void handleGetContextStrategy(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            ContextStrategy strategy = this.ctx.getSessionService().getContextStrategy(sessionId);
            ctx.json(Map.of("success", true, "strategy", strategy.name()));
        } catch (Exception e) {
            log.error("Error getting context strategy: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleSetContextStrategy(Context ctx) {
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
                this.ctx.getSessionService().initializeBranchingStrategy(sessionId);
            }

            this.ctx.getSessionService().updateContextStrategy(sessionId, strategy);

            ctx.json(Map.of("success", true, "message", "Стратегия контекста обновлена: " + strategy.name()));
        } catch (Exception e) {
            log.error("Error setting context strategy: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleGetFacts(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            var facts = this.ctx.getSessionService().getFacts(sessionId);
            ctx.json(Map.of("success", true, "facts", facts));
        } catch (Exception e) {
            log.error("Error getting facts: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleSaveFact(Context ctx) {
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

            var fact = this.ctx.getSessionService().saveFact(sessionId, category, key, value);
            ctx.json(Map.of("success", true, "fact", fact));
        } catch (Exception e) {
            log.error("Error saving fact: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleUpdateFact(Context ctx) {
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

            var fact = this.ctx.getSessionService().updateFact(factId, category, key, value);
            ctx.json(Map.of("success", true, "fact", fact));
        } catch (Exception e) {
            log.error("Error updating fact: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleDeleteFact(Context ctx) {
        try {
            long factId = Long.parseLong(ctx.pathParam("factId"));
            this.ctx.getSessionService().deleteFact(factId);
            ctx.json(Map.of("success", true, "message", "Fact deleted"));
        } catch (Exception e) {
            log.error("Error deleting fact: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleExtractFacts(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            this.ctx.getSessionService().extractFactsFromLastMessage(sessionId);
            ctx.json(Map.of("success", true, "message", "Извлечение фактов запущено"));
        } catch (Exception e) {
            log.error("Error extracting facts: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleGetStickyFactsSettings(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            int windowSize = this.ctx.getSessionService().getStickyFactsWindowSize(sessionId);
            ctx.json(Map.of("success", true, "stickyFactsWindowSize", windowSize));
        } catch (Exception e) {
            log.error("Error getting sticky facts settings: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleSetStickyFactsSettings(Context ctx) {
        try {
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            Integer windowSize = (Integer) request.get("stickyFactsWindowSize");

            if (windowSize == null || windowSize < 1 || windowSize > 100) {
                ctx.status(400).json(Map.of("success", false, "error", "stickyFactsWindowSize должен быть от 1 до 100"));
                return;
            }

            long sessionId = Long.parseLong(ctx.pathParam("id"));
            this.ctx.getSessionService().updateStickyFactsWindowSize(sessionId, windowSize);
            
            ctx.json(Map.of("success", true, "message", "Sticky Facts window size обновлён: " + windowSize));
        } catch (Exception e) {
            log.error("Error setting sticky facts settings: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleGetBranches(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            var branches = this.ctx.getSessionService().getBranches(sessionId);
            var activeBranch = this.ctx.getSessionService().getActiveBranch(sessionId);

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

    public void handleCreateBranch(Context ctx) {
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

            var branch = this.ctx.getSessionService().createBranch(sessionId, name, checkpointMessageId);
            ctx.json(Map.of("success", true, "branch", branch));
        } catch (Exception e) {
            log.error("Error creating branch: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleSwitchBranch(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            long branchId = Long.parseLong(ctx.pathParam("branchId"));

            this.ctx.getSessionService().switchBranch(sessionId, branchId);
            ctx.json(Map.of("success", true, "message", "Ветка переключена"));
        } catch (Exception e) {
            log.error("Error switching branch: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleDeleteBranch(Context ctx) {
        try {
            long branchId = Long.parseLong(ctx.pathParam("branchId"));

            this.ctx.getSessionService().deleteBranch(branchId);
            ctx.json(Map.of("success", true, "message", "Ветка удалена"));
        } catch (Exception e) {
            log.error("Error deleting branch: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleGetBranchStats(Context ctx) {
        long sessionId = Long.parseLong(ctx.pathParam("id"));
        long branchId = Long.parseLong(ctx.pathParam("branchId"));
        log.info("Get branch stats: session_id={}, branch_id={}", sessionId, branchId);
        
        var stats = this.ctx.getSessionService().getBranchStats(sessionId, branchId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("stats", Map.of(
            "totalTokens", stats.totalTokens(),
            "totalCost", stats.totalCost(),
            "requestCount", stats.requestCount()
        ));
        ctx.json(response);
    }

    public void handleGetCompressionSettings(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            int keepMessages = this.ctx.getSessionService().getCompressionKeepMessages(sessionId);
            int summaryInterval = this.ctx.getSessionService().getCompressionSummaryInterval(sessionId);
            ctx.json(Map.of("success", true, 
                "compressionKeepMessages", keepMessages,
                "compressionSummaryInterval", summaryInterval));
        } catch (Exception e) {
            log.error("Error getting compression settings: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleSetCompressionSettings(Context ctx) {
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
            this.ctx.getSessionService().updateCompressionSettings(sessionId, keepMessages, summaryInterval);
            
            ctx.json(Map.of("success", true, "message", "Compression settings обновлены"));
        } catch (Exception e) {
            log.error("Error setting compression settings: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleGetSlidingWindowSettings(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            int windowSize = this.ctx.getSessionService().getSlidingWindowSize(sessionId);
            ctx.json(Map.of("success", true, "slidingWindowSize", windowSize));
        } catch (Exception e) {
            log.error("Error getting sliding window settings: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleSetSlidingWindowSettings(Context ctx) {
        try {
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            Integer windowSize = (Integer) request.get("slidingWindowSize");

            if (windowSize == null || windowSize < 1 || windowSize > 100) {
                ctx.status(400).json(Map.of("success", false, "error", "slidingWindowSize должен быть от 1 до 100"));
                return;
            }

            long sessionId = Long.parseLong(ctx.pathParam("id"));
            this.ctx.getSessionService().updateSlidingWindowSize(sessionId, windowSize);
            
            ctx.json(Map.of("success", true, "message", "Sliding Window size обновлён: " + windowSize));
        } catch (Exception e) {
            log.error("Error setting sliding window settings: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }
}
