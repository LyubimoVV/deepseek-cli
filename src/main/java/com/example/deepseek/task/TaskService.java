package com.example.deepseek.task;

import com.example.deepseek.client.ClientManager;
import com.example.deepseek.db.DatabaseConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository taskRepository;
    private final TaskContextRepository contextRepository;
    private final TaskOrchestrator orchestrator;
    private final TaskMessageRepository taskMessageRepository;
    private final ObjectMapper objectMapper;

    public record TaskWithPlanResult(
        TaskDto task,
        TaskMessageDto planMessage
    ) {}

    public TaskService(ClientManager clientManager) {
        this.taskRepository = new TaskRepository();
        this.contextRepository = new TaskContextRepository();
        this.taskMessageRepository = new TaskMessageRepository();
        this.orchestrator = new TaskOrchestrator(clientManager, taskMessageRepository, null);
        this.objectMapper = new ObjectMapper();
    }

    public TaskService(ClientManager clientManager, com.example.deepseek.db.SessionService sessionService) {
        this.taskRepository = new TaskRepository();
        this.contextRepository = new TaskContextRepository();
        this.taskMessageRepository = new TaskMessageRepository();
        this.orchestrator = new TaskOrchestrator(clientManager, taskMessageRepository, sessionService);
        this.objectMapper = new ObjectMapper();
    }

    public TaskService() {
        this(null, null);
    }

    public TaskDto createTask(long sessionId, String title, String description) throws SQLException {
        return createTask(sessionId, title, description, TaskState.PLANNING);
    }

    public TaskDto createTask(long sessionId, String title, String description, TaskState initialState) throws SQLException {
        long taskId = taskRepository.createTask(sessionId, title, description, initialState);
        return taskRepository.getTaskById(taskId).orElseThrow();
    }

    public Optional<TaskDto> getTask(long taskId) throws SQLException {
        return taskRepository.getTaskById(taskId);
    }

    public List<TaskDto> getTasksBySession(long sessionId) throws SQLException {
        return taskRepository.getTasksBySessionId(sessionId);
    }

    public TaskDto updateTask(long taskId, String title, String description) throws SQLException {
        taskRepository.updateTask(taskId, title, description);
        return taskRepository.getTaskById(taskId).orElseThrow();
    }

    public TaskDto transitionTask(long taskId, TaskState newState, String expectedAction, long sessionId) throws SQLException {
        TaskDto task = taskRepository.getTaskById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        log.info("[transitionTask] START: taskId={}, currentState={}, targetState={}", taskId, task.state(), newState);

        if (task.state() == newState) {
            log.warn("[transitionTask] Task {} already in state {}, skipping transition", taskId, newState);
            return task;
        }

        log.info("[transitionTask] Transitioning task {} from {} to {}", taskId, task.state(), newState);

        TaskStateMachine.validateTransition(task.state(), newState);
        log.info("[transitionTask] State machine validation passed");

        try {
            DatabaseConfig.beginTransaction();
            
            taskRepository.updateTaskState(taskId, newState, expectedAction);
            log.info("[transitionTask] Updated task_repository for task {}", taskId);
            
            contextRepository.updateStateOnly(taskId, newState);
            log.info("[transitionTask] Updated task_context for task {}", taskId);
            
            DatabaseConfig.commitTransaction();
            log.info("[transitionTask] Transaction committed for task {}", taskId);
        } catch (SQLException e) {
            DatabaseConfig.rollbackTransaction();
            log.error("[transitionTask] Transaction rolled back for task {}: {}", taskId, e.getMessage());
            throw e;
        }

        TaskDto result = taskRepository.getTaskById(taskId).orElseThrow();
        log.info("[transitionTask] END: taskId={}, finalState={}", taskId, result.state());
        return result;
    }

    public TaskDto transitionTask(long taskId, TaskState newState, String expectedAction) throws SQLException {
        return transitionTask(taskId, newState, expectedAction, 0);
    }

    public TaskDto pauseTask(long taskId, String reason) throws SQLException {
        taskRepository.pauseTask(taskId, reason);
        return taskRepository.getTaskById(taskId).orElseThrow();
    }

    public TaskDto resumeTask(long taskId) throws SQLException {
        taskRepository.resumeTask(taskId);
        return taskRepository.getTaskById(taskId).orElseThrow();
    }

    public void saveTaskContext(long taskId, Map<String, Object> context) throws SQLException {
        try {
            String contextJson = objectMapper.writeValueAsString(context);
            taskRepository.updateTaskContext(taskId, contextJson);
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to serialize task context", e);
        }
    }

    public void deleteTask(long taskId) throws SQLException {
        contextRepository.deleteContext(taskId);
        taskRepository.deleteTask(taskId);
    }

    public void deleteTasksBySession(long sessionId) throws SQLException {
        taskRepository.deleteTasksBySessionId(sessionId);
    }

    public List<TaskState> getValidTransitions(TaskState currentState) {
        return TaskStateMachine.getValidTransitions(currentState);
    }

    public TaskWithPlanResult createTaskWithPlan(long sessionId, String title, String description) throws SQLException {
        if (orchestrator == null) {
            throw new IllegalStateException("ClientManager not configured for TaskService");
        }

        TaskDto task = createTask(sessionId, title, description, TaskState.PLANNING);
        log.info("Task created: id={}, state={}", task.id(), task.state());

        try {
            List<String> plan = orchestrator.generatePlan(description, sessionId, task.id());

            TaskContext context = new TaskContext(
                description,
                TaskState.PLANNING,
                1,
                plan.size(),
                plan,
                new ArrayList<>(),
                plan.isEmpty() ? "" : plan.get(0)
            );

            contextRepository.createContext(task.id(), context);
            log.info("Task context created for task id={}, plan size={}", task.id(), plan.size());

            TaskDto updatedTask = taskRepository.getTaskById(task.id()).orElseThrow();
            TaskMessageDto planMessage = taskMessageRepository.getByTaskIdAndState(task.id(), TaskState.PLANNING)
                .orElseThrow(() -> new SQLException("Plan message not found"));

            return new TaskWithPlanResult(updatedTask, planMessage);
        } catch (RuntimeException e) {
            taskRepository.deleteTask(task.id());
            throw new SQLException("Failed to generate plan: " + e.getMessage(), e);
        }
    }

    public Optional<TaskContext> getTaskContext(long taskId) throws SQLException {
        return contextRepository.getContextByTaskId(taskId);
    }

    public void updateTaskContext(long taskId, TaskContext context) throws SQLException {
        contextRepository.updateContext(taskId, context);
    }

    public void incrementStep(long taskId) throws SQLException {
        Optional<TaskContext> ctxOpt = getTaskContext(taskId);
        if (ctxOpt.isEmpty()) {
            throw new IllegalArgumentException("Task context not found: " + taskId);
        }

        TaskContext ctx = ctxOpt.get();
        int newStep = ctx.step() + 1;

        if (newStep > ctx.total()) {
            throw new IllegalStateException("Cannot increment beyond total steps");
        }

        String current = newStep <= ctx.plan().size() ? ctx.plan().get(newStep - 1) : "";

        TaskContext updated = new TaskContext(
            ctx.task(),
            ctx.state(),
            newStep,
            ctx.total(),
            ctx.plan(),
            ctx.done(),
            current
        );

        contextRepository.updateContext(taskId, updated);
    }

    public void addDone(long taskId, String step) throws SQLException {
        Optional<TaskContext> ctxOpt = getTaskContext(taskId);
        if (ctxOpt.isEmpty()) {
            throw new IllegalArgumentException("Task context not found: " + taskId);
        }

        TaskContext ctx = ctxOpt.get();
        List<String> done = new ArrayList<>(ctx.done());
        done.add(step);

        TaskContext updated = new TaskContext(
            ctx.task(),
            ctx.state(),
            ctx.step(),
            ctx.total(),
            ctx.plan(),
            done,
            ctx.current()
        );

        contextRepository.updateContext(taskId, updated);
    }

    public void updateCurrent(long taskId, String current) throws SQLException {
        Optional<TaskContext> ctxOpt = getTaskContext(taskId);
        if (ctxOpt.isEmpty()) {
            throw new IllegalArgumentException("Task context not found: " + taskId);
        }

        TaskContext ctx = ctxOpt.get();
        TaskContext updated = new TaskContext(
            ctx.task(),
            ctx.state(),
            ctx.step(),
            ctx.total(),
            ctx.plan(),
            ctx.done(),
            current
        );

        contextRepository.updateContext(taskId, updated);
    }

    public TaskOrchestrator.ValidationResult validateOnly(long taskId, String currentResult, long sessionId) throws SQLException {
        if (orchestrator == null) {
            throw new IllegalStateException("ClientManager not configured for TaskService");
        }

        Optional<TaskContext> ctxOpt = getTaskContext(taskId);
        if (ctxOpt.isEmpty()) {
            throw new IllegalArgumentException("Task context not found: " + taskId);
        }

        TaskContext ctx = ctxOpt.get();
        log.info("validateOnly: task {} current state={}, validating result", taskId, ctx.state());
        
        TaskOrchestrator.ValidationResult result = orchestrator.validateAndTransition(taskId, ctx, currentResult, sessionId);
        
        log.info("validateOnly: task {} validation result: success={}, suggestedState={}", 
            taskId, result.success(), result.nextState());
        
        return result;
    }

    public TaskOrchestrator.ValidationResult validateAndTransition(long taskId, String currentResult, long sessionId) throws SQLException {
        if (orchestrator == null) {
            throw new IllegalStateException("ClientManager not configured for TaskService");
        }

        Optional<TaskContext> ctxOpt = getTaskContext(taskId);
        if (ctxOpt.isEmpty()) {
            throw new IllegalArgumentException("Task context not found: " + taskId);
        }

        TaskContext ctx = ctxOpt.get();
        TaskOrchestrator.ValidationResult result = orchestrator.validateAndTransition(taskId, ctx, currentResult, sessionId);

        log.info("validateAndTransition: task {} from {} to {}, success={}", 
            taskId, ctx.state(), result.nextState(), result.success());
        
        TaskStateMachine.validateTransition(ctx.state(), result.nextState());
        taskRepository.updateTaskState(taskId, result.nextState(), result.message());
        contextRepository.updateStateOnly(taskId, result.nextState());

        return result;
    }

    public TaskOrchestrator.ValidationResult validateAndTransition(long taskId, String currentResult) throws SQLException {
        return validateAndTransition(taskId, currentResult, 0);
    }

    public TaskOrchestrator.ValidationResult validateAndAdvance(long taskId, String currentResult, long sessionId) throws SQLException {
        Optional<TaskContext> ctxOpt = getTaskContext(taskId);
        if (ctxOpt.isEmpty()) {
            throw new IllegalArgumentException("Task context not found: " + taskId);
        }

        TaskContext ctx = ctxOpt.get();

        if (ctx.state() == TaskState.PLANNING) {
            log.info("Task {} is in PLANNING state, skipping validation", taskId);
            return new TaskOrchestrator.ValidationResult(true, "Переход на выполнение", TaskState.EXECUTION);
        }

        if (ctx.state() == TaskState.EXECUTION) {
            log.info("Auto-validating task {} after response", taskId);
            return validateAndTransition(taskId, currentResult, sessionId);
        }

        return new TaskOrchestrator.ValidationResult(false, "Нет необходимости в валидации", ctx.state());
    }

    public String buildPrompt(String query, TaskContext ctx) {
        if (orchestrator == null) {
            throw new IllegalStateException("ClientManager not configured for TaskService");
        }
        return orchestrator.buildPrompt(query, ctx);
    }

    public Optional<TaskDto> getActiveTask(long sessionId) throws SQLException {
        List<TaskDto> tasks = taskRepository.getTasksBySessionId(sessionId);
        return tasks.stream()
            .filter(t -> !t.state().equals(TaskState.DONE) && !t.paused())
            .findFirst();
    }

    public Optional<TaskDto> getLatestTask(long sessionId) throws SQLException {
        return taskRepository.getLatestTaskBySessionId(sessionId);
    }

    public TaskMessageRepository getTaskMessageRepository() {
        return taskMessageRepository;
    }

    public void updateTaskAfterResponse(long taskId, String response) throws SQLException {
        Optional<TaskContext> ctxOpt = getTaskContext(taskId);
        if (ctxOpt.isEmpty()) {
            return;
        }

        TaskContext ctx = ctxOpt.get();

        if (response.toLowerCase().contains("готово") || 
            response.toLowerCase().contains("завершено") ||
            response.toLowerCase().contains("готово")) {
            if (ctx.step() < ctx.total()) {
                addDone(taskId, ctx.current());
                incrementStep(taskId);
            } else {
                TaskDto task = taskRepository.getTaskById(taskId).orElseThrow();
                taskRepository.updateTaskState(taskId, TaskState.DONE, "Все шаги выполнены");
            }
        }
    }

    public String buildStepPrompt(String userMessage, TaskContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Контекст задачи:\n");
        prompt.append("Описание: ").append(context.task()).append("\n");
        prompt.append("Текущий шаг (").append(context.step()).append("/").append(context.total()).append("): ");
        prompt.append(context.current()).append("\n\n");
        prompt.append("Сообщение пользователя:\n");
        prompt.append(userMessage);
        return prompt.toString();
    }

    public String generateTaskSummary(long taskId) throws SQLException {
        Optional<TaskDto> taskOpt = taskRepository.getTaskById(taskId);
        if (taskOpt.isEmpty()) {
            return "Задача не найдена.";
        }

        TaskDto task = taskOpt.get();
        Optional<TaskContext> ctxOpt = getTaskContext(taskId);

        if (ctxOpt.isEmpty()) {
            return String.format("Задача '%s' завершена.", task.title());
        }

        TaskContext ctx = ctxOpt.get();
        StringBuilder summary = new StringBuilder();
        summary.append("✨ Задача завершена!\n\n");
        summary.append("**").append(task.title()).append("**\n\n");
        summary.append("Выполненные шаги:\n");

        for (String doneStep : ctx.done()) {
            summary.append("✅ ").append(doneStep).append("\n");
        }

        if (!ctx.done().isEmpty()) {
            summary.append("\nВсего выполнено: ").append(ctx.done().size()).append(" шагов.\n");
        }

        return summary.toString();
    }
}
