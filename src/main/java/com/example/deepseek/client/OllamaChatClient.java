package com.example.deepseek.client;

import com.example.deepseek.context.ContextStrategyFactory;
import com.example.deepseek.db.SessionRepository;
import com.example.deepseek.dto.LlmResponse;
import com.example.deepseek.dto.Message;
import com.example.deepseek.dto.RequestMetrics;
import com.example.deepseek.dto.TokenUsage;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OllamaChatClient extends AbstractAiClient {

    private static final String DEFAULT_BASE_URL = "http://localhost:11434";
    private static final int TIMEOUT_SECONDS = 120;

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String modelName;

    public OllamaChatClient(String modelName) {
        super();
        this.baseUrl = DEFAULT_BASE_URL;
        this.modelName = modelName;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = createObjectMapper();
    }

    public OllamaChatClient(String modelName, String baseUrl) {
        super();
        this.baseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URL;
        this.modelName = modelName;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = createObjectMapper();
    }

    public OllamaChatClient(String modelName, String systemMessage, 
                           ContextStrategyFactory strategyFactory, SessionRepository sessionRepository) {
        super(systemMessage, strategyFactory, sessionRepository);
        this.baseUrl = DEFAULT_BASE_URL;
        this.modelName = modelName;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = createObjectMapper();
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        return mapper;
    }

    @Override
    protected String sendApiRequest(String userMessage) throws AiException {
        List<Message> messages = getMessagesForRequest(false);
        return sendChatRequest(messages);
    }

    @Override
    protected LlmResponse sendApiRequestWithMessages(List<Message> messages) throws AiException {
        try {
            OllamaChatRequest request = buildChatRequest(messages, false);
            String json = objectMapper.writeValueAsString(request);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new AiException("Ollama API error: " + response.statusCode() + " - " + response.body());
            }

            OllamaChatResponse chatResponse = objectMapper.readValue(response.body(), OllamaChatResponse.class);
            
            int inputTokens = chatResponse.promptEvalCount() != null ? chatResponse.promptEvalCount() : 0;
            int outputTokens = chatResponse.evalCount() != null ? chatResponse.evalCount() : 0;
            
            TokenUsage tokenUsage = new TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens);
            
            RequestMetrics metrics = new RequestMetrics(
                inputTokens,
                outputTokens,
                inputTokens + outputTokens,
                0,
                0,
                0.0,
                modelName
            );
            updateLastMetrics(metrics);

            String content = chatResponse.message() != null ? chatResponse.message().content() : "";
            return new LlmResponse(content, tokenUsage);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Failed to send request to Ollama: " + e.getMessage(), e);
        }
    }

    private String sendChatRequest(List<Message> messages) throws AiException {
        LlmResponse response = sendApiRequestWithMessages(messages);
        return response.content();
    }

    private OllamaChatRequest buildChatRequest(List<Message> messages, boolean stream) {
        List<OllamaMessage> ollamaMessages = new ArrayList<>();
        for (Message msg : messages) {
            ollamaMessages.add(new OllamaMessage(msg.role(), msg.content()));
        }

        OllamaChatRequest.OllamaChatRequestBuilder builder = OllamaChatRequest.builder()
            .model(modelName)
            .messages(ollamaMessages)
            .stream(stream);

        if (isMaxTokensEnabled()) {
            builder.numPredict(getMaxTokens());
        }

        if (isTemperatureEnabled()) {
            builder.options(Map.of("temperature", getTemperature()));
        }

        if (isStopSequencesEnabled() && !getStopSequences().isEmpty()) {
            builder.options(Map.of("stop", getStopSequences()));
        }

        return builder.build();
    }

    @Override
    public String getCurrentModel() {
        return modelName;
    }

    @Override
    public String getModelDisplayName() {
        return "Ollama: " + modelName;
    }

    @Override
    public String getProviderName() {
        return "Ollama (Local)";
    }

    public static boolean isOllamaAvailable() {
        return isOllamaAvailable(DEFAULT_BASE_URL);
    }

    public static boolean isOllamaAvailable(String baseUrl) {
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .GET()
                .timeout(Duration.ofSeconds(3))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public static List<String> getAvailableModels() {
        return getAvailableModels(DEFAULT_BASE_URL);
    }

    public static List<String> getAvailableModels(String baseUrl) {
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return List.of();
            }

            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            OllamaTagsResponse tagsResponse = mapper.readValue(response.body(), OllamaTagsResponse.class);

            List<String> models = new ArrayList<>();
            if (tagsResponse.models() != null) {
                for (OllamaModel model : tagsResponse.models()) {
                    models.add(model.name());
                }
            }
            return models;
        } catch (Exception e) {
            return List.of();
        }
    }
}

record OllamaMessage(String role, String content) {}

record OllamaChatRequest(
    String model,
    List<OllamaMessage> messages,
    boolean stream,
    Map<String, Object> options,
    Integer numPredict
) {
    public static OllamaChatRequestBuilder builder() {
        return new OllamaChatRequestBuilder();
    }

    public static class OllamaChatRequestBuilder {
        private String model;
        private List<OllamaMessage> messages;
        private boolean stream;
        private Map<String, Object> options;
        private Integer numPredict;

        public OllamaChatRequestBuilder model(String model) {
            this.model = model;
            return this;
        }

        public OllamaChatRequestBuilder messages(List<OllamaMessage> messages) {
            this.messages = messages;
            return this;
        }

        public OllamaChatRequestBuilder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public OllamaChatRequestBuilder options(Map<String, Object> options) {
            this.options = options;
            return this;
        }

        public OllamaChatRequestBuilder numPredict(Integer numPredict) {
            this.numPredict = numPredict;
            return this;
        }

        public OllamaChatRequest build() {
            return new OllamaChatRequest(model, messages, stream, options, numPredict);
        }
    }
}

record OllamaChatResponse(
    String model,
    OllamaResponseMessage message,
    Boolean done,
    Integer promptEvalCount,
    Integer evalCount
) {}

record OllamaResponseMessage(String role, String content) {}

record OllamaTagsResponse(List<OllamaModel> models) {}

record OllamaModel(String name, String modifiedAt, long size) {}
