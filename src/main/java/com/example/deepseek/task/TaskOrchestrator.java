package com.example.deepseek.task;

import com.example.deepseek.client.ClientManager;
import com.example.deepseek.db.SessionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class TaskOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TaskOrchestrator.class);
    private static final String PLAN_GENERATION_MODEL = "deepseek-chat";

    private final ClientManager clientManager;
    private final ObjectMapper objectMapper;
    private final TaskMessageRepository taskMessageRepository;
    private final SessionService sessionService;

    public TaskOrchestrator(ClientManager clientManager, TaskMessageRepository taskMessageRepository, SessionService sessionService) {
        this.clientManager = clientManager;
        this.taskMessageRepository = taskMessageRepository;
        this.sessionService = sessionService;
        this.objectMapper = new ObjectMapper();
    }

    public TaskOrchestrator(ClientManager clientManager) {
        this(clientManager, new TaskMessageRepository(), null);
    }

    public List<String> generatePlan(String taskDescription, long sessionId, long taskId) {
        String prompt = String.format("""
            Составь подробный пошаговый план выполнения следующей задачи:

            ЗАДАЧА:
            %s

            Требования к плану:
            - Разбей задачу на конкретные, измеримые шаги
            - Каждый шаг должен иметь конкретный результат, быть понятен и выполним
            - Порядок шагов должен быть логичным и последовательным
            - Верни только JSON массив строк, без дополнительных комментариев

            Пример формата:
            ["Шаг 1: ...", "Шаг 2: ...", "Шаг 3: ..."]
            """, taskDescription);

        try {
            String response = clientManager.chat(0, prompt, null);
            String jsonPart = extractJsonArray(response);
            List<String> plan = objectMapper.readValue(jsonPart, new TypeReference<List<String>>() {});

            if (taskMessageRepository != null && taskId > 0) {
                taskMessageRepository.saveMessage(taskId, TaskState.PLANNING, prompt, response, 0);
            }

            return plan;
        } catch (Exception e) {
            log.error("Failed to generate plan: {}", e.getMessage());
            throw new RuntimeException("Не удалось сгенерировать план", e);
        }
    }

    public List<String> generatePlan(String taskDescription) {
        return generatePlan(taskDescription, 0, 0);
    }

    public ValidationResult validateAndTransition(long taskId, TaskContext context, String currentResult, long sessionId) {
        String prompt = buildValidationPrompt(taskId, context);

        try {
            log.info("[Orchestrator.validateAndTransition] Sending validation prompt for task {}", taskId);
            String response = clientManager.chat(0, prompt, null);
            log.info("[Orchestrator.validateAndTransition] LLM response for task {}: {}", 
                taskId, response.length() > 500 ? response.substring(0, 500) + "..." : response);
            
            ValidationResult result = parseValidationResponse(response);
            log.info("[Orchestrator.validateAndTransition] Parsed result for task {}: success={}, nextState={}", 
                taskId, result.success(), result.nextState());

            return result;
        } catch (Exception e) {
            log.error("[Orchestrator.validateAndTransition] Failed for task {}: {}", taskId, e.getMessage());
            return new ValidationResult(
                false,
                "Ошибка валидации: " + e.getMessage(),
                TaskState.PLANNING
            );
        }
    }

    public ValidationResult validateAndTransition(long taskId, TaskContext context, String currentResult) {
        return validateAndTransition(taskId, context, currentResult, 0);
    }

    public String buildPrompt(String query, TaskContext ctx) {
        return String.format("""
            [STATE] %s, step %d/%d
            [CURRENT] %s
            [PLAN] %s
            [DONE] %s
            [QUERY] %s

            Правила:
            - Работай только в рамках текущего шага (current)
            - Не перепрыгивай этапы
            - Если шаг завершён — добавь в конец ответа маркер: [STEP_COMPLETE]
            - Если нужна дополнительная информация — укажи: [NEEDS_INPUT: что нужно]
            """,
            ctx.state().name(),
            ctx.step(),
            ctx.total(),
            ctx.current(),
            String.join(", ", ctx.plan()),
            String.join(", ", ctx.done()),
            query
        );
    }

    private String buildValidationPrompt(long taskId, TaskContext context) {
        List<TaskMessageDto> execMessages = List.of();
        String execSummary = "";
        try {
            execMessages = taskMessageRepository.getAllByTaskIdAndState(taskId, TaskState.EXECUTION);
            execSummary = execMessages.stream()
                .map(m -> "Шаг " + m.stepIndex() + ":\n" + truncate(m.response(), 2000))
                .collect(Collectors.joining("\n\n"));
        } catch (SQLException e) {
            log.error("Failed to get execution messages for task {}: {}", taskId, e.getMessage());
        }
        
        String planSummary = String.join("\n", context.plan());
        
        return String.format("""
            Сравни план задачи с фактически выполненным и реши о переходе.

            [ЗАДАЧА] %s
            [ПЛАН - %d шагов]
            %s
            [ВЫПОЛНЕНО - %d/%d]
            %s

            Требования:
            1. Все ли пункты плана выполнены корректно?
            2. Если все шаги выполнены → nextState: "DONE"
            3. Если не все → nextState: "EXECUTION"
            4. Если план требует изменения → nextState: "PLANNING"

            Ответь в формате JSON:
            {"success": true/false, "message": "краткое обоснование", "nextState": "PLANNING|EXECUTION|DONE"}
            """,
            context.task(),
            context.total(),
            planSummary,
            execMessages.size(),
            context.total(),
            execSummary
        );
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    private ValidationResult parseValidationResponse(String response) {
        try {
            String jsonPart = extractJsonObject(response);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(jsonPart, Map.class);

            boolean success = Boolean.TRUE.equals(map.get("success"));
            String message = (String) map.get("message");
            String nextStateStr = (String) map.get("nextState");
            TaskState nextState = TaskState.valueOf(nextStateStr);

            return new ValidationResult(success, message, nextState);
        } catch (Exception e) {
            log.error("Failed to parse validation response: {}", e.getMessage());
            return new ValidationResult(
                false,
                "Ошибка парсинга ответа валидации",
                TaskState.PLANNING
            );
        }
    }

    private String extractJsonArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start == -1 || end == -1) {
            throw new RuntimeException("JSON array not found in response");
        }
        return text.substring(start, end + 1);
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start == -1 || end == -1) {
            throw new RuntimeException("JSON object not found in response");
        }
        return text.substring(start, end + 1);
    }

    public record ValidationResult(
        boolean success,
        String message,
        TaskState nextState
    ) {}
}
