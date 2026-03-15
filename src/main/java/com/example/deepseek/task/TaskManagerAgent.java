package com.example.deepseek.task;

import com.example.deepseek.client.ClientManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public class TaskManagerAgent {

    private static final Logger log = LoggerFactory.getLogger(TaskManagerAgent.class);

    private static final Pattern[] TASK_KEYWORDS = {
        Pattern.compile("сдела[тйью]?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("созда[тй]?[ть]?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("реализу[тй]?[й]?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("разрабо[тй]?[ть]?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("напиши\\s+[а-я]+\\s+(класс|модуль|компонент|функцию)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("задач", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bmodule\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bfeature\\b", Pattern.CASE_INSENSITIVE)
    };

    private final ClientManager clientManager;
    private final ObjectMapper objectMapper;

    public TaskManagerAgent(ClientManager clientManager) {
        this.clientManager = clientManager;
        this.objectMapper = new ObjectMapper();
    }

    public TaskAnalysisResult analyze(String userMessage, Optional<TaskContext> currentContext) {
        if (currentContext.isPresent()) {
            return analyzeWithActiveTask(userMessage, currentContext.get());
        } else {
            return analyzeWithoutActiveTask(userMessage);
        }
    }

    private TaskAnalysisResult analyzeWithActiveTask(String userMessage, TaskContext context) {
        if (isTaskRelated(userMessage)) {
            return new TaskAnalysisResult(
                false,
                "Сообщение относится к активной задаче",
                TaskAction.CONTINUE_TASK
            );
        }

        if (isTaskComplete(userMessage)) {
            return new TaskAnalysisResult(
                false,
                "Задача завершена или переформулирована",
                TaskAction.COMPLETE_TASK
            );
        }

        if (isNewTask(userMessage)) {
            return new TaskAnalysisResult(
                true,
                extractTaskDescription(userMessage),
                TaskAction.CREATE_NEW_TASK
            );
        }

        return new TaskAnalysisResult(
            false,
            "Обычный запрос",
            TaskAction.NORMAL_CHAT
        );
    }

    private TaskAnalysisResult analyzeWithoutActiveTask(String userMessage) {
        if (isTaskRelated(userMessage)) {
            return new TaskAnalysisResult(
                true,
                extractTaskDescription(userMessage),
                TaskAction.CREATE_NEW_TASK
            );
        }

        return new TaskAnalysisResult(
            false,
            "Нет необходимости в задаче",
            TaskAction.NORMAL_CHAT
        );
    }

    private boolean isTaskRelated(String message) {
        for (Pattern pattern : TASK_KEYWORDS) {
            if (pattern.matcher(message).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean isTaskComplete(String message) {
        String lower = message.toLowerCase();
        return lower.contains("заверш") ||
               lower.contains("готово") ||
               lower.contains("сделано") ||
               lower.contains("достаточ") ||
               lower.contains("отмен");
    }

    private boolean isNewTask(String message) {
        String lower = message.toLowerCase();
        return lower.contains("нов") && lower.contains("задач") ||
               lower.startsWith("добави ") ||
               lower.startsWith("ещё ");
    }

    private String extractTaskDescription(String message) {
        String cleaned = message.replaceAll("[!?.,;]+$", "").trim();
        if (cleaned.length() > 200) {
            return cleaned.substring(0, 200) + "...";
        }
        return cleaned;
    }

    public String analyzeWithLLM(String userMessage, Optional<TaskContext> currentContext) {
        String prompt = buildAnalysisPrompt(userMessage, currentContext);

        try {
            String response = clientManager.chat(0, prompt, null);
            return parseAnalysisResponse(response);
        } catch (Exception e) {
            log.error("Failed to analyze with LLM: {}", e.getMessage());
            return null;
        }
    }

    private String buildAnalysisPrompt(String userMessage, Optional<TaskContext> currentContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Проанализируй сообщение пользователя и реши:");
        prompt.append("\n\n");
        prompt.append("СООБЩЕНИЕ: ").append(userMessage);
        prompt.append("\n\n");

        if (currentContext.isPresent()) {
            TaskContext ctx = currentContext.get();
            prompt.append("АКТИВНАЯ ЗАДАЧА:\n");
            prompt.append("Состояние: ").append(ctx.state()).append("\n");
            prompt.append("Текущий шаг: ").append(ctx.step()).append("/").append(ctx.total()).append("\n");
            prompt.append("Текущий шаг: ").append(ctx.current()).append("\n");
            prompt.append("План: ").append(String.join(", ", ctx.plan())).append("\n\n");
        }

        prompt.append("Ответь в формате JSON:\n");
        prompt.append("{\n");
        prompt.append("  \"needsTask\": true/false,\n");
        prompt.append("  \"taskDescription\": \"описание задачи или null\",\n");
        prompt.append("  \"action\": \"CREATE_NEW_TASK\" | \"CONTINUE_TASK\" | \"COMPLETE_TASK\" | \"NORMAL_CHAT\",\n");
        prompt.append("  \"reason\": \"краткое обоснование\"\n");
        prompt.append("}");

        return prompt.toString();
    }

    private String parseAnalysisResponse(String response) {
        try {
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start == -1 || end == -1) {
                return null;
            }
            return response.substring(start, end + 1);
        } catch (Exception e) {
            log.error("Failed to parse analysis response: {}", e.getMessage());
            return null;
        }
    }

    public record TaskAnalysisResult(
        boolean needsTask,
        String description,
        TaskAction action
    ) {}

    public enum TaskAction {
        CREATE_NEW_TASK,
        CONTINUE_TASK,
        COMPLETE_TASK,
        NORMAL_CHAT
    }
}
