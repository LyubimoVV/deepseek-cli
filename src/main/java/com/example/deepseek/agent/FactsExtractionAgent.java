package com.example.deepseek.agent;

import com.example.deepseek.client.AiClient;
import com.example.deepseek.client.ClientManager;
import com.example.deepseek.db.FactsRepository;
import com.example.deepseek.db.FactDto;
import com.example.deepseek.db.MessageDto;
import com.example.deepseek.db.MessageRepository;
import com.example.deepseek.dto.LlmResponse;
import com.example.deepseek.dto.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class FactsExtractionAgent {

    private static final Logger log = LoggerFactory.getLogger(FactsExtractionAgent.class);

    private static final String FACTS_MODEL = "deepseek-chat";
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_SECONDS = 2;

    private static final String FACTS_EXTRACTION_PROMPT = """
        Ты - агент по извлечению фактов из диалога. Твоя задача - анализировать сообщения пользователя и извлекать важные факты в формате JSON.

        Доступные категории фактов:
        - цель: цели, задачи, что пользователь хочет достичь
        - ограничения: ограничения, правила, запреты
        - предпочтения: предпочтения пользователя, стиль работы
        - решения: принятые решения, выбранные варианты
        - договорённости: договорённости, обещания
        - другое: прочие важные факты

        Формат ответа (только JSON, без дополнительного текста):
        {
          "facts": [
            {"category": "категория", "key": "ключ", "value": "значение"}
          ]
        }

        Правила:
        1. Извлекай только новые факты, которых нет в существующих
        2. Обновляй значения существующих фактов если они изменились
        3. Ключи должны быть краткими и понятными
        4. Значения должны содержать суть факта
        5. Не создавай дубликаты
        """;

    private final ClientManager clientManager;
    private final FactsRepository factsRepository;
    private final MessageRepository messageRepository;
    private final ExecutorService executor;

    public FactsExtractionAgent(ClientManager clientManager, FactsRepository factsRepository) {
        this.clientManager = clientManager;
        this.factsRepository = factsRepository;
        this.messageRepository = new MessageRepository();
        this.executor = Executors.newCachedThreadPool();
    }

    public void extractFactsFromUserMessage(long sessionId, String userMessage) {
        CompletableFuture.runAsync(() -> {
            try {
                extractFactsWithRetry(sessionId, userMessage, 1);
            } catch (Exception e) {
                log.error("Ошибка при извлечении фактов для сессии {}: {}", sessionId, e.getMessage());
            }
        }, executor);
    }

    public void extractFactsFromLastMessages(long sessionId) {
        CompletableFuture.runAsync(() -> {
            try {
                var messages = messageRepository.getMessagesBySession(sessionId);
                if (messages.isEmpty()) {
                    return;
                }

                var lastUserMessage = messages.stream()
                    .filter(m -> "user".equals(m.role()))
                    .reduce((first, second) -> second)
                    .orElse(null);

                if (lastUserMessage != null) {
                    extractFactsWithRetry(sessionId, lastUserMessage.content(), 1);
                }
            } catch (Exception e) {
                log.error("Ошибка при извлечении фактов из последних сообщений для сессии {}: {}", sessionId, e.getMessage());
            }
        }, executor);
    }

    private void extractFactsWithRetry(long sessionId, String userMessage, int attempt) throws Exception {
        try {
            extractFacts(sessionId, userMessage);
        } catch (Exception e) {
            log.warn("Попытка {} извлечения фактов для сессии {} завершилась с ошибкой: {}", 
                attempt, sessionId, e.getMessage());

            if (attempt < MAX_RETRIES) {
                Thread.sleep(RETRY_DELAY_SECONDS * 1000);
                extractFactsWithRetry(sessionId, userMessage, attempt + 1);
            } else {
                throw e;
            }
        }
    }

    public void extractFacts(long sessionId, String userMessage) throws Exception {
        log.debug("Extracting facts from user message for sessionId={}", sessionId);

        var existingFacts = factsRepository.getFactsBySession(sessionId);
        String existingFactsStr = formatExistingFacts(existingFacts);

        StringBuilder content = new StringBuilder();
        if (!existingFacts.isEmpty()) {
            content.append("Существующие факты:\n").append(existingFactsStr).append("\n\n");
        }
        content.append("Сообщение пользователя:\n").append(userMessage);

        List<Message> messagesForExtraction = new ArrayList<>();
        messagesForExtraction.add(Message.system(FACTS_EXTRACTION_PROMPT));
        messagesForExtraction.add(Message.user(content.toString()));

        AiClient client = getFactsModelClient();
        if (client == null) {
            log.warn("Facts model not found, using current client");
            client = clientManager.getCurrentClient();
        }

        LlmResponse response = client.chatWithMessages(messagesForExtraction);
        String responseContent = response.content();

        List<Map<String, String>> extractedFacts = parseFactsFromResponse(responseContent);

        for (var fact : extractedFacts) {
            String category = fact.get("category");
            String key = fact.get("key");
            String value = fact.get("value");

            if (category != null && key != null && value != null) {
                factsRepository.saveFact(sessionId, category, key, value);
                log.info("Fact saved: sessionId={}, category={}, key={}", sessionId, category, key);
            }
        }

        log.info("Facts extraction completed: sessionId={}, extractedCount={}", 
            sessionId, extractedFacts.size());
    }

    public void extractFactsManually(long sessionId, String content) {
        CompletableFuture.runAsync(() -> {
            try {
                extractFacts(sessionId, content);
            } catch (Exception e) {
                log.error("Ошибка при ручном извлечении фактов для сессии {}: {}", sessionId, e.getMessage());
            }
        }, executor);
    }

    private String formatExistingFacts(List<FactDto> facts) {
        if (facts.isEmpty()) {
            return "Нет сохранённых фактов";
        }

        StringBuilder sb = new StringBuilder();
        String currentCategory = null;

        for (var fact : facts) {
            if (!fact.category().equals(currentCategory)) {
                currentCategory = fact.category();
                sb.append("\n## ").append(currentCategory).append(":\n");
            }
            sb.append("- ").append(fact.key()).append(": ").append(fact.value()).append("\n");
        }

        return sb.toString();
    }

    private List<Map<String, String>> parseFactsFromResponse(String response) {
        List<Map<String, String>> facts = new ArrayList<>();

        try {
            int jsonStart = response.indexOf('{');
            int jsonEnd = response.lastIndexOf('}');

            if (jsonStart == -1 || jsonEnd == -1) {
                log.warn("No JSON found in response: {}", response);
                return facts;
            }

            String jsonStr = response.substring(jsonStart, jsonEnd + 1);
            jsonStr = jsonStr.replace("```json", "").replace("```", "").trim();

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(jsonStr);

            com.fasterxml.jackson.databind.JsonNode factsNode = rootNode.get("facts");
            if (factsNode != null && factsNode.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode factNode : factsNode) {
                    String category = factNode.has("category") ? factNode.get("category").asText() : "другое";
                    String key = factNode.has("key") ? factNode.get("key").asText() : "";
                    String value = factNode.has("value") ? factNode.get("value").asText() : "";

                    if (!key.isEmpty()) {
                        facts.add(Map.of("category", category, "key", key, "value", value));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error parsing facts from response: {}", e.getMessage());
        }

        return facts;
    }

    private AiClient getFactsModelClient() {
        if (clientManager.hasClient(FACTS_MODEL)) {
            return clientManager.getClient(FACTS_MODEL);
        }
        return clientManager.getCurrentClient();
    }

    public void shutdown() {
        executor.shutdown();
    }
}
