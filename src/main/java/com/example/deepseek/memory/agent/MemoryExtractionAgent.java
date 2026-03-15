package com.example.deepseek.memory.agent;

import com.example.deepseek.client.AiClient;
import com.example.deepseek.client.ClientManager;
import com.example.deepseek.memory.MemoryLayer;
import com.example.deepseek.memory.MemoryScope;
import com.example.deepseek.memory.dto.MemorySuggestion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class MemoryExtractionAgent {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionAgent.class);

    private static final String EXTRACTION_MODEL = "deepseek-chat";

    private static final String EXTRACTION_PROMPT = """
        Ты - агент по анализу диалога для предложения сохранения в память.
        Твоя задача - анализировать текст и предлагать, что можно сохранить в память.
        НЕ выполняй сохранение автоматически, только ПРЕДЛАГАЙ варианты.

        Типы слоёв памяти:
        - WORKING (рабочая память): текущая задача, переменные, временные данные, решения в рамках текущей сессии
        - LONG_TERM (долговременная память): профиль пользователя, предпочтения, знания, которые нужно сохранить навсегда

        Категории:
        - task: цели, задачи, текущий контекст работы
        - preferences: предпочтения пользователя
        - profile: профильная информация
        - knowledge: знания, факты, решения
        - variables: переменные, промежуточные результаты
        - constraints: ограничения, правила

        Формат ответа (только JSON, без дополнительного текста):
        {
          "suggestions": [
            {
              "key": "ключ_на_английском",
              "value": "значение кратко",
              "layer": "WORKING или LONG_TERM",
              "category": "категория",
              "confidence": 0.0-1.0,
              "explanation": "почему это важно"
            }
          ]
        }

        Правила:
        1. Предлагай только действительно важные факты
        2. Ключи должны быть на английском, краткие и понятные
        3. Confidence должен быть реалистичным (0.7-1.0 для уверенных, ниже для сомнительных)
        4. LONG_TERM - для постоянных предпочтений и знаний
        5. WORKING - для временных данных текущей задачи
        6. Не предлагай тривиальные факты (приветствия, стандартные фразы)
        """;

    private final ClientManager clientManager;
    private final ObjectMapper objectMapper;

    public MemoryExtractionAgent(ClientManager clientManager) {
        this.clientManager = clientManager;
        this.objectMapper = new ObjectMapper();
    }

    public List<MemorySuggestion> analyze(String content, MemoryScope scope) {
        log.debug("Analyzing content for memory suggestions: scope={}", scope);

        try {
            String prompt = buildPrompt(content, scope);

            AiClient client = getClient(EXTRACTION_MODEL);
            if (client == null) {
                log.warn("Extraction model not available, using current client");
                client = clientManager.getCurrentClient();
            }

            var messages = new ArrayList<com.example.deepseek.dto.Message>();
            messages.add(com.example.deepseek.dto.Message.system(EXTRACTION_PROMPT));
            messages.add(com.example.deepseek.dto.Message.user(prompt));

            var response = client.chatWithMessages(messages);
            String responseContent = response.content();

            return parseSuggestions(responseContent);
        } catch (Exception e) {
            log.error("Error analyzing content for memory suggestions: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private String buildPrompt(String content, MemoryScope scope) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Анализируй следующий текст и предложи, что сохранить в память:\n\n");
        prompt.append("Текст:\n").append(content).append("\n\n");

        return prompt.toString();
    }

    private List<MemorySuggestion> parseSuggestions(String response) {
        List<MemorySuggestion> suggestions = new ArrayList<>();

        try {
            int jsonStart = response.indexOf('{');
            int jsonEnd = response.lastIndexOf('}');

            if (jsonStart == -1 || jsonEnd == -1) {
                log.warn("No JSON found in response: {}", response);
                return suggestions;
            }

            String jsonStr = response.substring(jsonStart, jsonEnd + 1);
            jsonStr = jsonStr.replace("```json", "").replace("```", "").trim();

            JsonNode rootNode = objectMapper.readTree(jsonStr);
            JsonNode suggestionsNode = rootNode.get("suggestions");

            if (suggestionsNode != null && suggestionsNode.isArray()) {
                for (JsonNode suggestionNode : suggestionsNode) {
                    try {
                        String key = suggestionNode.has("key") ? suggestionNode.get("key").asText() : null;
                        String value = suggestionNode.has("value") ? suggestionNode.get("value").asText() : null;
                        String layerStr = suggestionNode.has("layer") ? suggestionNode.get("layer").asText() : "WORKING";
                        String category = suggestionNode.has("category") ? suggestionNode.get("category").asText() : "task";
                        double confidence = suggestionNode.has("confidence") ? suggestionNode.get("confidence").asDouble() : 0.5;
                        String explanation = suggestionNode.has("explanation") ? suggestionNode.get("explanation").asText() : "";

                        if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                            MemoryLayer layer;
                            try {
                                layer = MemoryLayer.valueOf(layerStr.toUpperCase());
                            } catch (IllegalArgumentException e) {
                                layer = MemoryLayer.WORKING;
                            }

                            suggestions.add(new MemorySuggestion(key, value, layer, category, confidence, explanation));
                        }
                    } catch (Exception e) {
                        log.warn("Error parsing suggestion: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error parsing suggestions from response: {}", e.getMessage());
        }

        return suggestions;
    }

    private AiClient getClient(String modelName) {
        if (clientManager.hasClient(modelName)) {
            return clientManager.getClient(modelName);
        }
        return null;
    }
}
