package com.example.deepseek.client;

import com.example.deepseek.config.AppConfig;
import com.example.deepseek.context.ContextStrategyFactory;
import com.example.deepseek.db.SessionRepository;
import com.example.deepseek.dto.*;
import com.example.deepseek.mcp.McpToolIntegrationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DeepSeekClient extends AbstractAiClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);
    
    private static final String API_URL = "https://api.deepseek.com/v1/chat/completions";
    public static final String MODEL_CHAT = "deepseek-chat";
    public static final String MODEL_REASONER = "deepseek-reasoner";
    private static final Duration TIMEOUT = Duration.ofSeconds(600);

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private List<String> stopSequences = new ArrayList<>();
    private boolean stopSequencesEnabled = false;
    private boolean thinkingEnabled = true;
    private String currentModel;
    private McpToolIntegrationService mcpToolIntegrationService;

    public DeepSeekClient(String apiKey) {
        this(apiKey, MODEL_REASONER);
    }

    public DeepSeekClient(String apiKey, String model) {
        this(apiKey, model, DEFAULT_SYSTEM_MESSAGE);
    }

    public DeepSeekClient(String apiKey, String model, String systemMessage) {
        super(systemMessage);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be null or blank");
        }
        this.apiKey = apiKey;
        this.currentModel = validateModel(model);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public DeepSeekClient(String apiKey, String model, String systemMessage,
                          ContextStrategyFactory strategyFactory, SessionRepository sessionRepository) {
        super(systemMessage, strategyFactory, sessionRepository);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be null or blank");
        }
        this.apiKey = apiKey;
        this.currentModel = validateModel(model);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public DeepSeekClient(String apiKey, String model, HttpClient httpClient, ObjectMapper objectMapper) {
        super();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be null or blank");
        }
        this.apiKey = apiKey;
        this.currentModel = validateModel(model);
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public DeepSeekClient(String apiKey, String model, HttpClient httpClient, ObjectMapper objectMapper,
                          ContextStrategyFactory strategyFactory, SessionRepository sessionRepository) {
        super(strategyFactory, sessionRepository);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be null or blank");
        }
        this.apiKey = apiKey;
        this.currentModel = validateModel(model);
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public void setMcpToolIntegrationService(McpToolIntegrationService service) {
        this.mcpToolIntegrationService = service;
        log.info("McpToolIntegrationService set for DeepSeekClient");
    }

    private String validateModel(String model) {
        if (model == null || model.isBlank()) {
            return MODEL_REASONER;
        }
        if (!model.equals(MODEL_CHAT) && !model.equals(MODEL_REASONER)) {
            throw new IllegalArgumentException("Model must be '" + MODEL_CHAT + "' or '" + MODEL_REASONER + "'");
        }
        return model;
    }

    @Override
    protected String sendApiRequest(String userMessage) throws AiException {
        List<ToolDto> tools = getMcpTools();
        boolean hasTools = tools != null && !tools.isEmpty();
        
        List<Message> messages = getMessagesForRequest(hasTools);
        
        ChatRequest request = buildChatRequestWithSettings(messages, tools);
        ChatResponse chatResponse = sendHttpRequestRaw(request);
        
        if (chatResponse.hasToolCalls() && mcpToolIntegrationService != null) {
            return executeToolLoop(messages, chatResponse, tools);
        }
        
        updateMetricsFromResponse(chatResponse);
        return chatResponse.getContent();
    }

    private List<ToolDto> getMcpTools() {
        if (mcpToolIntegrationService == null || currentSessionId <= 0) {
            return null;
        }
        
        List<ToolDto> tools = mcpToolIntegrationService.getToolsForSession(currentSessionId);
        return tools.isEmpty() ? null : tools;
    }

    private String executeToolLoop(List<Message> originalMessages, ChatResponse initialResponse, List<ToolDto> tools) throws AiException {
        List<Message> messages = new ArrayList<>(originalMessages);
        ChatResponse currentResponse = initialResponse;
        int iterations = 0;
        int maxIterations = mcpToolIntegrationService.getMaxToolIterations();
        
        while (currentResponse.hasToolCalls() && iterations < maxIterations) {
            iterations++;
            List<ToolCallDto> toolCalls = currentResponse.getToolCalls();
            log.info("Tool loop iteration {}, {} tool calls", iterations, toolCalls.size());
            
            messages.add(Message.assistantWithTools(currentResponse.getContent(), toolCalls));
            
            for (ToolCallDto toolCall : toolCalls) {
                McpToolIntegrationService.ToolExecutionResult result = mcpToolIntegrationService.executeToolCall(toolCall);
                messages.add(Message.toolResult(toolCall.id(), toolCall.function().name(), result.result()));
                log.debug("Tool {} executed, success={}", toolCall.function().name(), result.success());
            }
            
            ChatRequest nextRequest = buildChatRequestWithSettings(messages, tools);
            currentResponse = sendHttpRequestRaw(nextRequest);
        }
        
        if (iterations >= maxIterations) {
            log.warn("Tool loop reached max iterations: {}", maxIterations);
        }
        
        updateMetricsFromResponse(currentResponse);
        return currentResponse.getContent();
    }

    private void updateMetricsFromResponse(ChatResponse chatResponse) {
        Usage usage = chatResponse.getUsage();
        int cachedTokens = usage.getCachedTokens();
        double cost = PricingService.calculateCost(currentModel, usage.promptTokens(), usage.completionTokens());
        updateLastMetrics(new RequestMetrics(
                usage.promptTokens(),
                usage.completionTokens(),
                usage.totalTokens(),
                cachedTokens,
                0,
                cost,
                currentModel
        ));
    }

    protected ChatRequest buildChatRequestWithSettings(List<Message> messages) {
        return buildChatRequestWithSettings(messages, null);
    }

    protected ChatRequest buildChatRequestWithSettings(List<Message> messages, List<ToolDto> tools) {
        Integer tokens = maxTokensEnabled ? maxTokens : null;
        List<String> stop = stopSequencesEnabled && !stopSequences.isEmpty() ? new ArrayList<>(stopSequences) : null;
        Double temp = temperatureEnabled ? temperature : null;

        Map<String, String> thinkingParam = null;
        if (currentModel.equals(MODEL_REASONER)) {
            if (!thinkingEnabled) {
                thinkingParam = Map.of("type", "disabled");
            }
        } else {
            if (thinkingEnabled) {
                thinkingParam = Map.of("type", "enabled");
            }
        }

        return new ChatRequest(currentModel, messages, tokens, stop, temp, thinkingParam, tools);
    }

    @Override
    protected LlmResponse sendApiRequestWithMessages(List<Message> messages) throws AiException {
        ChatRequest request = buildChatRequestWithSettings(messages);
        return sendHttpRequest(request);
    }

    protected LlmResponse sendHttpRequest(ChatRequest request) throws AiException {
        ChatResponse chatResponse = sendHttpRequestRaw(request);
        updateMetricsFromResponse(chatResponse);
        
        Usage usage = chatResponse.getUsage();
        return new LlmResponse(
            chatResponse.getContent(),
            new TokenUsage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens())
        );
    }

    protected ChatResponse sendHttpRequestRaw(ChatRequest request) throws AiException {
        List<Message> messages = request.messages();

        if (AppConfig.isTestMode()) {
            int estimatedTokens = estimateContextSize(messages);
            int limit = AppConfig.getContextLimit();
            if (estimatedTokens > limit) {
                throw new AiException("TEST MODE: Context limit exceeded. Estimated: " + estimatedTokens + " > " + limit);
            }
        }

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new AiException("Failed to serialize request", e);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("Request interrupted", e);
        } catch (Exception e) {
            throw AiException.networkError("Failed to send request: " + e.getMessage(), e);
        }

        if (response.statusCode() != 200) {
            throw new ApiException(
                    response.statusCode(),
                    "API returned error status: " + response.statusCode() + "\n" + response.body(),
                    response.body(),
                    API_URL,
                    requestBody
            );
        }

        ChatResponse chatResponse;
        try {
            chatResponse = objectMapper.readValue(response.body(), ChatResponse.class);
        } catch (JsonProcessingException e) {
            throw AiException.invalidResponse("Failed to deserialize response: " + e.getMessage());
        }

        return chatResponse;
    }

    public String chatLimited(String userMessage) throws AiException {
        boolean savedMaxTokensEnabled = maxTokensEnabled;
        boolean savedStopSequencesEnabled = stopSequencesEnabled;
        int savedMaxTokens = maxTokens;
        List<String> savedStopSequences = new ArrayList<>(stopSequences);

        maxTokensEnabled = true;
        stopSequencesEnabled = true;

        try {
            return chat(userMessage);
        } finally {
            maxTokensEnabled = savedMaxTokensEnabled;
            stopSequencesEnabled = savedStopSequencesEnabled;
            maxTokens = savedMaxTokens;
            stopSequences = savedStopSequences;
        }
    }

    @Override
    public String getCurrentModel() {
        return currentModel;
    }

    @Override
    public String getModelDisplayName() {
        return PricingService.getModelDisplayName(currentModel);
    }

    @Override
    public String getProviderName() {
        return "DeepSeek";
    }

    public List<String> getStopSequences() {
        return new ArrayList<>(stopSequences);
    }

    public void setStopSequences(List<String> stopSequences) {
        this.stopSequences = stopSequences != null ? new ArrayList<>(stopSequences) : new ArrayList<>();
    }

    public boolean isStopSequencesEnabled() {
        return stopSequencesEnabled;
    }

    public void setStopSequencesEnabled(boolean enabled) {
        this.stopSequencesEnabled = enabled;
    }

    public void setCurrentModel(String model) {
        this.currentModel = validateModel(model);
    }

    public boolean isThinkingEnabled() {
        return thinkingEnabled;
    }

    public void setThinkingEnabled(boolean enabled) {
        this.thinkingEnabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    private int estimateContextSize(List<Message> messages) {
        int total = 0;
        for (Message msg : messages) {
            total += msg.content().length() / 4;
        }
        return total;
    }
}
