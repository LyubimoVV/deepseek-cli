package com.example.deepseek.app.controllers;

import com.example.deepseek.client.ClientManager;
import com.example.deepseek.dto.Message;
import com.example.deepseek.task.*;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.*;

public class TaskController {
    private static final Logger log = LoggerFactory.getLogger(TaskController.class);
    
    private final AppContext ctx;
    
    public TaskController(AppContext ctx) {
        this.ctx = ctx;
    }
    
    public void handleGetTasks(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            var tasks = this.ctx.getTaskService().getTasksBySession(sessionId);
            ctx.json(Map.of("success", true, "tasks", tasks));
        } catch (Exception e) {
            log.error("Error getting tasks: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleCreateTask(Context ctx) {
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

            var task = this.ctx.getTaskService().createTask(sessionId, title, description, initialState);
            ctx.json(Map.of("success", true, "task", task));
        } catch (Exception e) {
            log.error("Error creating task: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleGetTask(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            var task = this.ctx.getTaskService().getTask(taskId);
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

    public void handleUpdateTask(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            String title = (String) request.get("title");
            String description = (String) request.get("description");

            if (title == null || title.isBlank()) {
                ctx.status(400).json(Map.of("success", false, "error", "Параметр 'title' обязателен"));
                return;
            }

            var task = this.ctx.getTaskService().updateTask(taskId, title, description);
            ctx.json(Map.of("success", true, "task", task));
        } catch (Exception e) {
            log.error("Error updating task: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleDeleteTask(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            this.ctx.getTaskService().deleteTask(taskId);
            ctx.json(Map.of("success", true, "message", "Task deleted"));
        } catch (Exception e) {
            log.error("Error deleting task: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleTransitionTask(Context ctx) {
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

            var task = this.ctx.getTaskService().transitionTask(taskId, newState, expectedAction);
            ctx.json(Map.of("success", true, "task", task));
        } catch (IllegalStateException e) {
            log.error("Invalid task transition: {}", e.getMessage());
            ctx.status(400).json(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error transitioning task: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handlePauseTask(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            String reason = (String) request.get("reason");

            var task = this.ctx.getTaskService().pauseTask(taskId, reason);
            ctx.json(Map.of("success", true, "task", task));
        } catch (Exception e) {
            log.error("Error pausing task: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleResumeTask(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            var task = this.ctx.getTaskService().resumeTask(taskId);
            ctx.json(Map.of("success", true, "task", task));
        } catch (Exception e) {
            log.error("Error resuming task: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleCreateTaskWithPlan(Context ctx) {
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

            var result = this.ctx.getTaskService().createTaskWithPlan(sessionId, title, description);
            ctx.json(Map.of(
                "success", true,
                "task", result.task(),
                "planMessage", result.planMessage()
            ));
        } catch (Exception e) {
            log.error("Error creating task with plan: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleGetTaskContext(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            var context = this.ctx.getTaskService().getTaskContext(taskId);
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

    public void handleIncrementStep(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            this.ctx.getTaskService().incrementStep(taskId);
            ctx.json(Map.of("success", true, "message", "Step incremented"));
        } catch (Exception e) {
            log.error("Error incrementing step: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleAddDone(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            String step = (String) request.get("step");

            if (step == null || step.isBlank()) {
                ctx.status(400).json(Map.of("success", false, "error", "Параметр 'step' обязателен"));
                return;
            }

            this.ctx.getTaskService().addDone(taskId, step);
            ctx.json(Map.of("success", true, "message", "Step added to done"));
        } catch (Exception e) {
            log.error("Error adding done: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleUpdateCurrent(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            String current = (String) request.get("current");

            if (current == null || current.isBlank()) {
                ctx.status(400).json(Map.of("success", false, "error", "Параметр 'current' обязателен"));
                return;
            }

            this.ctx.getTaskService().updateCurrent(taskId, current);
            ctx.json(Map.of("success", true, "message", "Current step updated"));
        } catch (Exception e) {
            log.error("Error updating current: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleValidateAndTransition(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            Map<String, Object> request = ctx.bodyAsClass(Map.class);
            String currentResult = (String) request.get("currentResult");

            if (currentResult == null || currentResult.isBlank()) {
                ctx.status(400).json(Map.of("success", false, "error", "Параметр 'currentResult' обязателен"));
                return;
            }

            var result = this.ctx.getTaskService().validateAndTransition(taskId, currentResult);
            ctx.json(Map.of("success", true, "result", result));
        } catch (Exception e) {
            log.error("Error validating and transitioning: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleGetActiveTask(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            var activeTask = this.ctx.getTaskService().getActiveTask(sessionId);
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

    public void handleConfirmPlan(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            long sessionId = this.ctx.getSessionService().getCurrentSessionId();

            TaskDto task = this.ctx.getTaskService().getTask(taskId).orElseThrow(
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
            this.ctx.getTaskService().transitionTask(taskId, TaskState.EXECUTION, null, sessionId);
            
            TaskDto afterExecution = this.ctx.getTaskService().getTask(taskId).orElseThrow();
            log.info("[handleConfirmPlan] Task {} state after EXECUTION transition: {}", taskId, afterExecution.state());

            ctx.json(Map.of(
                "success", true,
                "message", "Выполнение начато",
                "taskId", taskId,
                "taskState", afterExecution.state().name()
            ));

            TaskController self = this;
            new Thread(() -> {
                try {
                    log.info("[AsyncExecution] Starting async execution for task {}", taskId);
                    List<StepResult> allResults = self.executeAllSteps(taskId, sessionId);
                    log.info("[AsyncExecution] Executed {} steps for task {}", allResults.size(), taskId);
                    
                    String finalResult = self.validateAndCompleteTask(taskId, sessionId, allResults);
                    log.info("[AsyncExecution] Final result for task {}: {}", taskId, 
                        finalResult.length() > 100 ? finalResult.substring(0, 100) + "..." : finalResult);

                    TaskDto finalTask = self.ctx.getTaskService().getTask(taskId).orElseThrow();
                    log.info("[AsyncExecution] END: Task {} final state: {}", taskId, finalTask.state());
                } catch (Exception e) {
                    log.error("[AsyncExecution] Error executing task {}: {}", taskId, e.getMessage(), e);
                    try {
                        self.ctx.getTaskService().transitionTask(taskId, TaskState.PLANNING, "Ошибка выполнения: " + e.getMessage(), sessionId);
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

    public void handleReplanTask(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            long sessionId = this.ctx.getSessionService().getCurrentSessionId();
            
            TaskDto task = this.ctx.getTaskService().getTask(taskId).orElseThrow(
                () -> new IllegalArgumentException("Task not found: " + taskId)
            );
            
            log.info("Replanning task id={}, current state={}", taskId, task.state());
            
            this.ctx.getTaskService().transitionTask(taskId, TaskState.PLANNING, "Возврат к планированию по запросу пользователя", sessionId);
            
            ctx.json(Map.of("success", true, "message", "Задача возвращена в планирование"));
        } catch (Exception e) {
            log.error("Error replanning task: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleGetTaskMessages(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            var messages = this.ctx.getTaskService().getTaskMessageRepository().getByTaskId(taskId);
            ctx.json(Map.of("success", true, "messages", messages));
        } catch (Exception e) {
            log.error("Error getting task messages: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleGetTaskMessagesByState(Context ctx) {
        try {
            long taskId = Long.parseLong(ctx.pathParam("taskId"));
            String stateStr = ctx.pathParam("state");
            TaskState taskState = TaskState.valueOf(stateStr);

            var messages = this.ctx.getTaskService().getTaskMessageRepository().getAllByTaskIdAndState(taskId, taskState);
            ctx.json(Map.of("success", true, "messages", messages));
        } catch (IllegalArgumentException e) {
            log.error("Invalid task state: {}", e.getMessage());
            ctx.status(400).json(Map.of("success", false, "error", "Неверное состояние: " + ctx.pathParam("state")));
        } catch (Exception e) {
            log.error("Error getting task messages by state: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private record StepResult(int stepNumber, String description, String result) {}

    private List<StepResult> executeAllSteps(long taskId, long sessionId) {
        List<StepResult> allResults = new ArrayList<>();
        
        try {
            while (true) {
                var taskCtxOpt = this.ctx.getTaskService().getTaskContext(taskId);
                if (taskCtxOpt.isEmpty()) {
                    log.error("Task context not found for task {}", taskId);
                    break;
                }

                TaskContext taskCtx = taskCtxOpt.get();
                int currentStep = taskCtx.step();
                int totalSteps = taskCtx.total();
                String currentDescription = taskCtx.current();

                log.info("Executing step {}/{} for task {}: {}", currentStep, totalSteps, taskId, currentDescription);

                String userMessage = taskCtx.task();
                String taskPrompt = this.ctx.getTaskService().buildPrompt(userMessage, taskCtx);

                List<Message> messages = List.of(Message.user(taskPrompt));
                String response = this.ctx.getClientManager().chatWithMessages(sessionId, messages);
                log.info("Received response for step {} from LLM: {}", currentStep, response.substring(0, Math.min(100, response.length())));

                this.ctx.getTaskService().getTaskMessageRepository().saveMessage(taskId, TaskState.EXECUTION, taskPrompt, response, 0, currentStep);

                allResults.add(new StepResult(currentStep, currentDescription, response));

                this.ctx.getTaskService().addDone(taskId, currentDescription);

                if (currentStep < totalSteps) {
                    this.ctx.getTaskService().incrementStep(taskId);
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

    private String validateAndCompleteTask(long taskId, long sessionId, List<StepResult> allResults) {
        try {
            log.info("[validateAndCompleteTask] Starting for task {}", taskId);

            String finalResult = combineAllResults(allResults);
            log.info("[validateAndCompleteTask] Combined result length: {}", finalResult.length());

            var taskCtxOpt = this.ctx.getTaskService().getTaskContext(taskId);
            if (taskCtxOpt.isEmpty()) {
                throw new IllegalArgumentException("Task context not found: " + taskId);
            }

            TaskContext taskCtx = taskCtxOpt.get();
            log.info("[validateAndCompleteTask] Task context: state={}, step={}/{}", 
                taskCtx.state(), taskCtx.step(), taskCtx.total());
            
            TaskOrchestrator.ValidationResult validationResult = 
                this.ctx.getTaskService().validateOnly(taskId, finalResult, sessionId);

            log.info("[validateAndCompleteTask] Validation result: success={}, suggestedNextState={}", 
                validationResult.success(), validationResult.nextState());

            this.ctx.getTaskService().getTaskMessageRepository().saveMessage(
                taskId, TaskState.VALIDATION, finalResult, 
                validationResult.message(), 0
            );

            TaskDto taskBeforeTransition = this.ctx.getTaskService().getTask(taskId).orElseThrow();
            log.info("[validateAndCompleteTask] Task state before transition: {}", taskBeforeTransition.state());

            if (validationResult.success()) {
                log.info("[validateAndCompleteTask] Validation SUCCESS, transitioning to DONE");
                this.ctx.getTaskService().transitionTask(taskId, TaskState.DONE, validationResult.message(), sessionId);
            } else {
                log.info("[validateAndCompleteTask] Validation FAILED, transitioning to PLANNING");
                this.ctx.getTaskService().transitionTask(taskId, TaskState.PLANNING, validationResult.message(), sessionId);
            }

            TaskDto taskAfterTransition = this.ctx.getTaskService().getTask(taskId).orElseThrow();
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

    private String combineAllResults(List<StepResult> allResults) {
        StringBuilder combined = new StringBuilder();
        combined.append("Результат выполнения задачи:\n\n");
        for (StepResult step : allResults) {
            combined.append("Шаг ").append(step.stepNumber()).append(": ").append(step.description()).append("\n");
            combined.append(step.result()).append("\n\n");
        }
        return combined.toString();
    }

    private String generateFinalMessage(List<StepResult> allResults, String validationMessage) {
        return String.format("✨ Задача выполнена!\n\nВыполнено шагов: %d\n\n%s", 
            allResults.size(), validationMessage
        );
    }

    private String generateValidationFailedMessage(String validationMessage) {
        return String.format("⚠️ Валидация не пройдена\n\n%s\n\nХотите вернуться к планированию?", 
            validationMessage
        );
    }
}
