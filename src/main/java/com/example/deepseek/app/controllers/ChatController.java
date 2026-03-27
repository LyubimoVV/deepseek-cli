package com.example.deepseek.app.controllers;

import com.example.deepseek.client.ClientManager;
import com.example.deepseek.db.MessageDto;
import com.example.deepseek.db.SessionService;
import com.example.deepseek.dto.Message;
import com.example.deepseek.dto.RequestMetrics;
import com.example.deepseek.invariant.InvariantViolationException;
import com.example.deepseek.invariant.ValidationResult;
import com.example.deepseek.task.TaskContext;
import com.example.deepseek.task.TaskDto;
import com.example.deepseek.task.TaskManagerAgent;
import com.example.deepseek.task.TaskService;
import com.example.deepseek.task.TaskState;
import com.example.deepseek.task.TaskMessageDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final AppContext ctx;
    
    public ChatController(AppContext ctx) {
        this.ctx = ctx;
    }
    
    public void handleChat(Context ctx) throws Exception {
        Map<String, Object> request = ctx.bodyAsClass(Map.class);
        String message = null;

        if (request.containsKey("messages")) {
            List<Map<String, String>> messages = (List<Map<String, String>>) request.get("messages");
            if (messages != null && !messages.isEmpty()) {
                long sessionId = this.ctx.getSessionService().getCurrentSessionId();
                log.info("Chat request: session_id={}, messages_count={}", sessionId, messages.size());

                for (Map<String, String> msg : messages) {
                    String role = msg.get("role");
                    String content = msg.get("content");
                    if (role != null && content != null) {
                        long messageId = this.ctx.getSessionService().saveMessage(role, content, 0, 0, 0, 0, 0, 0.0);
                        if ("user".equals(role)) {
                            this.ctx.getSessionService().onMessageSaved(this.ctx.getSessionService().getCurrentSessionId(), role, content);
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
            long sessionId = this.ctx.getSessionService().getCurrentSessionId();

            long userMessageId = this.ctx.getSessionService().saveMessage("user", message, 0, 0, 0, 0, 0, 0.0);
            this.ctx.getSessionService().onMessageSaved(sessionId, "user", message);

            String systemMessage = this.ctx.getSessionService().getSystemMessage(sessionId);

            var activeTask = this.ctx.isTsmEnabled() 
                ? this.ctx.getTaskService().getActiveTask(sessionId) 
                : Optional.<TaskDto>empty();
            log.info("Active task for session {}: {}", sessionId, activeTask.isPresent() ? activeTask.get().title() + " (state: " + activeTask.get().state() + ")" : "none");

            var activeContext = activeTask.flatMap(t -> {
                try {
                    Optional<TaskContext> taskCtx = this.ctx.getTaskService().getTaskContext(t.id());
                    if (taskCtx.isPresent()) {
                        log.info("Task context found for task {}: state={}, step={}/{}",
                            t.id(), taskCtx.get().state(), taskCtx.get().step(), taskCtx.get().total());
                    }
                    return taskCtx;
                } catch (Exception e) {
                    log.error("Error getting task context for task {}: {}", t.id(), e.getMessage());
                    return Optional.empty();
                }
            });

            var analysis = this.ctx.isTsmEnabled()
                ? this.ctx.getTaskManagerAgent().analyze(message, activeContext)
                : new TaskManagerAgent.TaskAnalysisResult(false, "TSM disabled", TaskManagerAgent.TaskAction.NORMAL_CHAT);

            Optional<TaskContext> finalContext = activeContext;
            String finalPrompt = message;

            if (analysis.needsTask() && activeContext.isEmpty()) {
                log.info("Creating new task: description={}", analysis.description());
                
                try {
                    var result = this.ctx.getTaskService().createTaskWithPlan(sessionId, "Задача из чата", analysis.description());

                    String planNoteContent = generateTaskNote(result.planMessage(), 0);
                    
                    String sessionTitle = this.ctx.getSessionService().generateTitleFromFirstMessageSync();

                    Map<String, Object> responseMap = new HashMap<>();
                    responseMap.put("response", "Задача создана. Подтвердите план для начала выполнения.");
                    responseMap.put("success", true);
                    responseMap.put("taskCreated", true);
                    responseMap.put("taskId", result.task().id());
                    responseMap.put("requiresConfirmation", true);
                    responseMap.put("taskPlanMessage", planNoteContent);
                    responseMap.put("userMessageId", userMessageId);

                    if (sessionTitle != null) {
                        responseMap.put("sessionTitle", sessionTitle);
                    }

                    ctx.json(responseMap);
                    return;
                } catch (InvariantViolationException e) {
                    ValidationResult.UserRequestViolation violation = e.getViolation();
                    String errorMessage = violation.formatMessage();
                    
                    this.ctx.getSessionService().saveMessage("assistant", errorMessage, 0, 0, 0, 0, 0, 0.0);
                    
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("success", false);
                    errorResponse.put("error", errorMessage);
                    errorResponse.put("violationType", "CONSTRAINT_VIOLATION");
                    errorResponse.put("requestedTech", violation.requestedTech());
                    errorResponse.put("allowedTech", violation.allowedTech());
                    errorResponse.put("userMessageId", userMessageId);
                    ctx.json(errorResponse);
                    return;
                }
            }

            if (activeTask.isPresent()) {
                var task = activeTask.get();
                Optional<TaskContext> taskCtxOpt = this.ctx.getTaskService().getTaskContext(task.id());

                if (taskCtxOpt.isPresent()) {
                    TaskContext taskCtx = taskCtxOpt.get();

                    if (taskCtx.state() == TaskState.PLANNING) {
                        Map<String, Object> responseMap = new HashMap<>();
                        responseMap.put("response", "Задача находится в состоянии планирования. Пожалуйста, подтвердите план для начала выполнения.");
                        responseMap.put("success", true);
                        responseMap.put("taskState", "PLANNING");
                        responseMap.put("requiresConfirmation", true);
                        responseMap.put("userMessageId", userMessageId);

                        ctx.json(responseMap);
                        return;
                    }

                    if (taskCtx.state() == TaskState.EXECUTION) {
                        log.info("Using buildStepPrompt for EXECUTION state. Message: {}", message);
                        log.info("Task context: step={}/{}, current={}", taskCtx.step(), taskCtx.total(), taskCtx.current());
                        finalPrompt = this.ctx.getTaskService().buildStepPrompt(message, taskCtx);
                        log.info("Generated step prompt: {}", finalPrompt);
                        finalContext = taskCtxOpt;
                    }

                    if (taskCtx.state() == TaskState.DONE) {
                        String summary = this.ctx.getTaskService().generateTaskSummary(task.id());
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
                finalPrompt = this.ctx.getTaskService().buildPrompt(message, finalContext.get());
            }

            log.info("Sending prompt to LLM: {}", finalPrompt);
            String response;
            if (finalContext.isPresent()) {
                response = this.ctx.getTaskService().chatWithRetry(sessionId, finalPrompt, systemMessage);
            } else {
                response = this.ctx.getClientManager().chat(sessionId, finalPrompt, systemMessage);
            }
            log.info("Chat response: session_id={}, response_length={}", sessionId, response != null ? response.length() : 0);
            long latency = System.currentTimeMillis() - startTime;
            var metrics = this.ctx.getClientManager().getLastMetrics();

            long assistantMessageId = this.ctx.getSessionService().saveMessage("assistant", response,
                metrics != null ? metrics.getInputTokens() : 0,
                metrics != null ? metrics.getOutputTokens() : 0,
                metrics != null ? metrics.getTotalTokens() : 0,
                metrics != null ? metrics.getCachedTokens() : 0,
                (int) latency,
                metrics != null ? metrics.getCostUsd() : 0.0);

            String needsInputReason = extractNeedsInput(response);
            if (needsInputReason != null && activeTask.isPresent()) {
                try {
                    this.ctx.getTaskService().pauseTask(activeTask.get().id(), needsInputReason);
                    log.info("Task {} paused due to NEEDS_INPUT: {}", activeTask.get().id(), needsInputReason);
                    
                    String sessionTitle = this.ctx.getSessionService().generateTitleFromFirstMessageSync();
                    
                    Map<String, Object> pausedResponseMap = new HashMap<>();
                    pausedResponseMap.put("response", response);
                    pausedResponseMap.put("success", true);
                    pausedResponseMap.put("taskPaused", true);
                    pausedResponseMap.put("pauseReason", needsInputReason);
                    pausedResponseMap.put("userMessageId", userMessageId);
                    pausedResponseMap.put("lastMessageId", assistantMessageId);
                    
                    if (sessionTitle != null) {
                        pausedResponseMap.put("sessionTitle", sessionTitle);
                    }
                    
                    if (metrics != null) {
                        pausedResponseMap.put("metrics", buildMetricsMap(metrics));
                    }
                    
                    ctx.json(pausedResponseMap);
                    return;
                } catch (Exception e) {
                    log.error("Failed to pause task: {}", e.getMessage());
                }
            }

            String sessionTitle = this.ctx.getSessionService().generateTitleFromFirstMessageSync();

            if (activeTask.isPresent()) {
                try {
                    this.ctx.getTaskService().validateAndAdvance(activeTask.get().id(), response, sessionId);
                    this.ctx.getTaskService().updateTaskAfterResponse(activeTask.get().id(), response);
                } catch (Exception e) {
                    log.error("Failed to update task after response: {}", e.getMessage());
                }
            }

            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("response", response);
            responseMap.put("success", true);
            responseMap.put("userMessageId", userMessageId);
            responseMap.put("lastMessageId", assistantMessageId);

            if (sessionTitle != null) {
                responseMap.put("sessionTitle", sessionTitle);
            }

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

    public void handleClear(Context ctx) {
        long oldSessionId = this.ctx.getSessionService().getCurrentSessionId();
        log.info("Clear history: old_session_id={}", oldSessionId);

        long profileId = this.ctx.getProfileIdForSession(oldSessionId);

        this.ctx.getClientManager().clearAllHistory();

        long newSessionId = this.ctx.getSessionService().createSession(
            "Новая сессия",
            this.ctx.getClientManager().getCurrentModel(),
            this.ctx.getClientManager().getSystemMessage(),
            2,
            profileId
        );

        log.info("Clear history: created new session_id={}", newSessionId);
        ctx.json(Map.of("success", true, "message", "История очищена, создана новая сессия"));
    }

    public void handleHistory(Context ctx) {
        long sessionId = this.ctx.getSessionService().getCurrentSessionId();

        List<MessageDto> sessionMessages = this.ctx.getSessionService().getSessionMessages(sessionId);

        List<ChatMessage> history = new ArrayList<>();
        for (var msg : sessionMessages) {
            history.add(new ChatMessage(msg.role(), msg.content(),
                msg.inputTokens(), msg.outputTokens(), msg.latency(), msg.cost(), msg.id(), false, null, null, null, msg.createdAt()));
        }

        boolean requiresConfirmation = false;
        long activeTaskId = 0;

        try {
            var activeTask = this.ctx.getTaskService().getActiveTask(sessionId);
            var taskForMessages = activeTask;
            
            log.info("[handleHistory] Active task lookup: found={}", activeTask.isPresent());
            
            if (taskForMessages.isEmpty()) {
                taskForMessages = this.ctx.getTaskService().getLatestTask(sessionId);
                log.info("[handleHistory] No active task, using latest task: {}", 
                    taskForMessages.isPresent() ? taskForMessages.get().id() + " (state=" + taskForMessages.get().state() + ")" : "none");
            } else {
                log.info("[handleHistory] Active task found: id={}, state={}", taskForMessages.get().id(), taskForMessages.get().state());
            }
            
            if (taskForMessages.isPresent()) {
                activeTaskId = taskForMessages.get().id();
                var taskMessages = this.ctx.getTaskService().getTaskMessageRepository().getByTaskId(activeTaskId);
                log.info("[handleHistory] Found {} task messages for task {}", taskMessages.size(), activeTaskId);

                int totalSteps = 0;
                try {
                    var taskCtxOpt = this.ctx.getTaskService().getTaskContext(activeTaskId);
                    if (taskCtxOpt.isPresent()) {
                        totalSteps = taskCtxOpt.get().total();
                    }
                } catch (Exception e) {
                    log.error("[handleHistory] Error getting task context: {}", e.getMessage());
                }

                for (var taskMsg : taskMessages) {
                    String noteContent = generateTaskNote(taskMsg, totalSteps);
                    history.add(new ChatMessage("system", noteContent, 0, 0, 0, 0.0, null,
                        true, activeTaskId, taskMsg.taskState().name(), taskMsg.stepIndex(), taskMsg.createdAt()));
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
            "taskRequiresConfirmation", requiresConfirmation,
            "activeTaskId", activeTaskId
        ));
    }

    public void handleLimited(Context ctx) throws Exception {
        log.info("Limited chat: start");
        Map<String, String> request = ctx.bodyAsClass(Map.class);
        String message = request.get("message");

        if (message == null || message.isBlank()) {
            ctx.status(400).json(Map.of("success", false, "error", "Сообщение не может быть пустым"));
            return;
        }

        try {
            var client = this.ctx.getClientManager().getCurrentClient();
            String response;

            if (client instanceof com.example.deepseek.client.DeepSeekClient) {
                response = ((com.example.deepseek.client.DeepSeekClient) client).chatLimited(message);
            } else {
                client.setMaxTokens(100);
                client.setMaxTokensEnabled(true);
                response = client.chat(message);
            }

            var metrics = this.ctx.getClientManager().getLastMetrics();

            this.ctx.getSessionService().saveMessage("user", message, 0, 0, 0, 0, 0, 0.0);
            this.ctx.getSessionService().saveMessageAsync("assistant", response,
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

    private Map<String, Object> buildMetricsMap(RequestMetrics metrics) {
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

    private String generateTaskNote(TaskMessageDto taskMsg, int totalSteps) {
        String icon = switch (taskMsg.taskState()) {
            case PLANNING -> "📋";
            case EXECUTION -> "⚡";
            case VALIDATION -> "✅";
            case DONE -> "✨";
            case PAUSED -> "⏸️";
        };

        String response = taskMsg.response();
        String stateLabel = taskMsg.taskState().name();

        if (taskMsg.taskState() == TaskState.PLANNING) {
            try {
                var plan = objectMapper.readValue(response, java.util.List.class);
                int planSize = plan.size();
                String planPreview = planSize <= 5 ? String.join("\n", plan.stream().map(Object::toString).toList())
                    : "Шаги выполнения: " + String.join(", ", plan.subList(0, 5).stream().map(Object::toString).toList()) + "...";
                return String.format("%s [%s] Создан план из %d шагов\n%s", icon, stateLabel, planSize, planPreview);
            } catch (Exception e) {
                log.error("Failed to parse plan from response: {}", e.getMessage());
            }
        }

        if (taskMsg.taskState() == TaskState.EXECUTION) {
            if (taskMsg.stepIndex() != null && totalSteps > 0) {
                stateLabel = String.format("EXECUTION %d/%d", taskMsg.stepIndex(), totalSteps);
            }
        }

        if (taskMsg.taskState() == TaskState.VALIDATION) {
            if (response.contains("\"success\":true")) {
                icon = "✅";
            } else {
                icon = "⚠️";
            }
        }

        String cleanResponse = response
            .replace("[STEP_COMPLETE]", "")
            .replaceAll("\n{3,}", "\n\n")
            .trim();
        return String.format("%s [%s] %s", icon, stateLabel,
            cleanResponse.length() > 200 ? cleanResponse.substring(0, 200) + "..." : cleanResponse);
    }

    private String extractNeedsInput(String response) {
        if (response == null) return null;
        int start = response.indexOf("[NEEDS_INPUT:");
        if (start == -1) return null;
        int end = response.indexOf("]", start);
        if (end == -1) return null;
        return response.substring(start + "[NEEDS_INPUT:".length(), end).trim();
    }
}
