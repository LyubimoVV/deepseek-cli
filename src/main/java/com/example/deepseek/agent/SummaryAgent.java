package com.example.deepseek.agent;

import com.example.deepseek.client.AiClient;
import com.example.deepseek.client.ClientManager;
import com.example.deepseek.db.GlobalSummaryDto;
import com.example.deepseek.db.GlobalSummaryRepository;
import com.example.deepseek.db.MessageDto;
import com.example.deepseek.db.MessageRepository;
import com.example.deepseek.db.SessionRepository;
import com.example.deepseek.db.SessionService;
import com.example.deepseek.dto.LlmResponse;
import com.example.deepseek.dto.Message;
import com.example.deepseek.dto.SummaryResult;
import com.example.deepseek.dto.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class SummaryAgent {

    private static final Logger log = LoggerFactory.getLogger(SummaryAgent.class);

    private static final String SUMMARY_MODEL = "deepseek-chat";
    private static final int MAX_RETRIES = 20;
    private static final int RETRY_DELAY_SECONDS = 5;
    private static final String SUMMARY_PROMPT = "Сжимай историю диалога. Формат истории: [user] сообщение | [assistant] ответ. Сообщения из Previous context должны быть в ответе, оставляй их как есть. Сохрани: основные мысли, логику, факты, решения, контекст, ограничения формата, инструкции, ключевые сущности, имена, числа, термины, условия. Игнорируй: приветствия, повторы. Пиши на языке диалога, без вводных слов.";
    private static final String SEPARATOR = " | ";

    String formatMessages(List<MessageDto> messages) {
        StringBuilder content = new StringBuilder();
        int messageCount = 0;

        for (MessageDto msg : messages) {
            content.append("[").append(msg.role()).append("] ");

            String msgContent = msg.content();
            content.append(msgContent != null ? msgContent.trim() : "");

            content.append(SEPARATOR);
            messageCount++;
        }

        if (messageCount > 0) {
            content.setLength(content.length() - SEPARATOR.length());
        }

        return content.toString();
    }

    private final ClientManager clientManager;
    private final SessionService sessionService;
    private final SessionRepository sessionRepository;
    private final GlobalSummaryRepository globalSummaryRepository;
    private final MessageRepository messageRepository;
    private final ExecutorService executor;

    public SummaryAgent(ClientManager clientManager, SessionService sessionService) {
        this.clientManager = clientManager;
        this.sessionService = sessionService;
        this.sessionRepository = new SessionRepository();
        this.globalSummaryRepository = new GlobalSummaryRepository();
        this.messageRepository = new MessageRepository();
        this.executor = Executors.newCachedThreadPool();
    }

    public GlobalSummaryRepository getGlobalSummaryRepository() {
        return globalSummaryRepository;
    }

    public MessageRepository getMessageRepository() {
        return messageRepository;
    }

    public CompletableFuture<String> compressAsync(long sessionId, List<Message> messages) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return compressWithRetry(sessionId, messages, null, 1);
            } catch (Exception e) {
                log.error("Ошибка при сжатии контекста для сессии {}: {}", sessionId, e.getMessage());
                return null;
            }
        }, executor);
    }

    public CompletableFuture<String> compressAsyncWithMessages(long sessionId, List<MessageDto> messages) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<Message> messageList = messages.stream()
                    .map(msg -> new Message(msg.role(), msg.content()))
                    .collect(java.util.stream.Collectors.toList());
                return compressWithRetry(sessionId, messageList, messages, 1);
            } catch (Exception e) {
                log.error("Ошибка при сжатии контекста для сессии {}: {}", sessionId, e.getMessage());
                return null;
            }
        }, executor);
    }

    private String compressWithRetry(long sessionId, List<Message> messages, List<MessageDto> messagesDto, int attempt) throws Exception {
        try {
            String summary = callSummaryModel(messages);
            return summary;
        } catch (Exception e) {
            log.warn("Попытка {} создания summary для сессии {} завершилась с ошибкой: {}", attempt, sessionId, e.getMessage());

            if (attempt < MAX_RETRIES) {
                Thread.sleep(RETRY_DELAY_SECONDS * 1000);
                return compressWithRetry(sessionId, messages, messagesDto, attempt + 1);
            }
            throw e;
        }
    }

    private String callSummaryModel(List<Message> messages) throws Exception {
        AiClient client = getSummaryModelClient();
        if (client == null) {
            throw new IllegalStateException("Модель для summary не найдена: " + SUMMARY_MODEL);
        }

        String systemInstruction = SUMMARY_PROMPT;

        StringBuilder content = new StringBuilder();
        content.append("История диалога:\n");

        for (Message msg : messages) {
            content.append("[").append(msg.role()).append("] ").append(msg.content().trim()).append(" ");
        }

        List<Message> messagesForSummary = new ArrayList<>();
        messagesForSummary.add(Message.system(systemInstruction));
        messagesForSummary.add(Message.user(content.toString().trim()));

        LlmResponse response = client.chatWithMessages(messagesForSummary);
        return response.content();
    }

    public List<Message> getCompressedContext(long sessionId, List<Message> allMessages, String systemMessage) {
        try {
            log.info("getCompressedContext: sessionId={}, allMessages.size={}", sessionId, allMessages.size());

            Optional<GlobalSummaryDto> globalSummary = globalSummaryRepository.getLatestGlobalSummary(sessionId);

            List<MessageDto> allRecentMessages;
            int recentCount;

            if (globalSummary.isPresent()) {
                long lastSummaryId = globalSummary.get().lastMessageId();
                allRecentMessages = messageRepository.getMessagesAfter(sessionId, lastSummaryId);
                recentCount = allRecentMessages.size();

                log.info("getCompressedContext: lastSummaryId={}, allAfterSummary.size={}",
                    lastSummaryId, recentCount);
            } else {
                allRecentMessages = messageRepository.getMessagesBySession(sessionId);
                recentCount = allRecentMessages.size();
            }

            List<Message> recentMessages = allRecentMessages.stream()
                .map(msg -> new Message(msg.role(), msg.content()))
                .collect(Collectors.toList());

            log.info("getCompressedContext: globalSummaryExists={}, recentMessagesCount={}, usedLimit={}",
                globalSummary.isPresent(), recentMessages.size(), recentCount);

            return buildHybridContext(systemMessage, globalSummary, recentMessages);
        } catch (Exception e) {
            log.error("Ошибка при формировании сжатого контекста для сессии {}: {}", sessionId, e.getMessage());
            return allMessages;
        }
    }

    private List<Message> buildHybridContext(String systemMessage, Optional<GlobalSummaryDto> globalSummary, List<Message> recentMessages) {
        List<Message> result = new ArrayList<>();

        result.add(Message.system(systemMessage));

        globalSummary.ifPresent(summary -> {
            result.add(Message.system("Контекст диалога: " + summary.content()));
            log.info("buildHybridContext: Added global summary, version={}", summary.version());
        });

        result.addAll(recentMessages);

        log.info("buildHybridContext OUTPUT: resultSize={}, hasSummary={}, recentMessagesCount={}",
            result.size(), globalSummary.isPresent(), recentMessages.size());

        return result;
    }

    private AiClient getSummaryModelClient() {
        if (!clientManager.hasClient(SUMMARY_MODEL)) {
            return clientManager.getCurrentClient();
        }
        return clientManager.getClient(SUMMARY_MODEL);
    }

    @Deprecated
    public String generateSummary(String input) throws Exception {
        AiClient client = getSummaryModelClient();
        if (client == null) {
            throw new IllegalStateException("Модель для summary не найдена: " + SUMMARY_MODEL);
        }

        String systemInstruction = SUMMARY_PROMPT;

        List<Message> messagesForSummary = new ArrayList<>();
        messagesForSummary.add(Message.system(systemInstruction));

        StringBuilder content = new StringBuilder();
        content.append("История диалога:\n");
        
        String[] lines = input.split("\n");
        boolean isFirst = true;
        for (String line : lines) {
            if (line.contains(": ")) {
                String[] parts = line.split(": ", 2);
                if (parts.length == 2) {
                    if (!isFirst) {
                        content.append(" ");
                    }
                    content.append("[").append(parts[0]).append("] ").append(parts[1].trim());
                    isFirst = false;
                }
            }
        }

        messagesForSummary.add(Message.user(content.toString()));

        LlmResponse response = client.chatWithMessages(messagesForSummary);
        return response.content();
    }

    public SummaryResult generateSummaryWithMetrics(
            Optional<GlobalSummaryDto> oldSummary,
            List<MessageDto> newMessages) throws Exception {
        
        AiClient client = getSummaryModelClient();
        if (client == null) {
            throw new IllegalStateException("Модель для summary не найдена: " + SUMMARY_MODEL);
        }

        String systemInstruction = SUMMARY_PROMPT;
        StringBuilder content = new StringBuilder();

        oldSummary.ifPresent(summary -> {
            content.append("Previous context: ")
                   .append(summary.content())
                   .append("\n\n");
        });

        content.append("New messages:\n");
        content.append(formatMessages(newMessages));

        log.info("generateSummaryWithMetrics: oldSummaryExists={}, newMessagesCount={}, promptLength={}",
            oldSummary.isPresent(), newMessages.size(), content.length());

        List<Message> messagesForSummary = new ArrayList<>();
        messagesForSummary.add(Message.system(systemInstruction));
        messagesForSummary.add(Message.user(content.toString()));

        long startTime = System.currentTimeMillis();
        LlmResponse response = client.chatWithMessages(messagesForSummary);
        long duration = System.currentTimeMillis() - startTime;

        String result = response.content();
        TokenUsage usage = response.tokenUsage();

        log.info("Summary generated: oldContext={}, newMessages={}, promptChars={}, responseChars={}, durationMs={}, inputTokens={}, outputTokens={}, totalTokens={}, cost={}",
            oldSummary.isPresent(),
            newMessages.size(),
            content.length(),
            result != null ? result.length() : 0,
            duration,
            usage.inputTokens(),
            usage.outputTokens(),
            usage.totalTokens(),
            calculateCost(usage.inputTokens(), usage.outputTokens(), SUMMARY_MODEL)
        );

        return new SummaryResult(result, usage.inputTokens(), usage.outputTokens(), usage.totalTokens(), calculateCost(usage.inputTokens(), usage.outputTokens(), SUMMARY_MODEL));
    }

    public String generateSummaryFromMessages(List<MessageDto> messages) throws Exception {
        SummaryResult result = generateSummaryWithMetrics(Optional.empty(), messages);
        return result.summary();
    }

    public String generateSummaryFromMessages(Optional<GlobalSummaryDto> oldSummary, List<MessageDto> messages) throws Exception {
        SummaryResult result = generateSummaryWithMetrics(oldSummary, messages);
        return result.summary();
    }

    private double calculateCost(int inputTokens, int outputTokens, String model) {
        double pricePerMillion;
        
        if (model.contains("reasoner")) {
            pricePerMillion = 0.55;
        } else {
            pricePerMillion = 0.14;
        }
        
        double totalTokens = inputTokens + outputTokens;
        return (totalTokens / 1_000_000.0) * pricePerMillion;
    }

    public void shutdown() {
        executor.shutdown();
    }
}
