package com.example.deepseek.context;

import com.example.deepseek.agent.SummaryAgent;
import com.example.deepseek.db.GlobalSummaryDto;
import com.example.deepseek.db.GlobalSummaryRepository;
import com.example.deepseek.db.MessageDto;
import com.example.deepseek.db.MessageRepository;
import com.example.deepseek.db.SessionDto;
import com.example.deepseek.db.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

public class ContextScheduler {

    private static final Logger log = LoggerFactory.getLogger(ContextScheduler.class);
    private static final int MAX_RETRIES = 10;
    private static final int RETRY_DELAY_SECONDS = 5;

    private final SummaryAgent summaryAgent;
    private final MessageRepository messageRepository;
    private final GlobalSummaryRepository globalSummaryRepository;
    private final SessionRepository sessionRepository;

    public ContextScheduler(SummaryAgent summaryAgent, MessageRepository messageRepository) {
        this.summaryAgent = summaryAgent;
        this.messageRepository = messageRepository;
        this.globalSummaryRepository = new GlobalSummaryRepository();
        this.sessionRepository = new SessionRepository();
    }

    public void scheduleAfterMessageSave(long sessionId, int totalMessageCount) {
        try {
            if (shouldCreateSummary(sessionId)) {
                createSummaryAsync(sessionId);
            }
        } catch (Exception e) {
            log.error("Ошибка при планировании создания summary для сессии {}: {}", sessionId, e.getMessage());
        }
    }

    private boolean shouldCreateSummary(long sessionId) {
        try {
            Optional<SessionDto> session = sessionRepository.getSession(sessionId);
            if (session.isEmpty()) {
                log.warn("shouldCreateSummary: session not found for sessionId={}", sessionId);
                return false;
            }

            ContextStrategy strategy = session.get().contextStrategy();
            if (strategy == ContextStrategy.SLIDING_WINDOW || strategy == ContextStrategy.STICKY_FACTS || strategy == ContextStrategy.NONE) {
                log.info("shouldCreateSummary: {} strategy, skipping summary for sessionId={}", strategy, sessionId);
                return false;
            }

            int keepMessages = sessionRepository.getCompressionKeepMessages(sessionId);
            int summaryInterval = sessionRepository.getCompressionSummaryInterval(sessionId);
            int summaryBufferSize = keepMessages + summaryInterval;

            Optional<GlobalSummaryDto> existingSummary = globalSummaryRepository.getLatestGlobalSummary(sessionId);
            Long lastMessageId = existingSummary.map(GlobalSummaryDto::lastMessageId).orElse(null);

            int messagesSinceLastSummary = messageRepository.getMessageCountAfterId(sessionId, lastMessageId);

            boolean triggerByBuffer = messagesSinceLastSummary >= summaryBufferSize;

            log.info("shouldCreateSummary: sessionId={}, messagesSinceLastSummary={}, bufferSize={}, trigger={}",
                sessionId, messagesSinceLastSummary, summaryBufferSize, triggerByBuffer);

            return triggerByBuffer;
        } catch (Exception e) {
            log.error("Ошибка при проверке триггера summary для сессии {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    private void createSummaryAsync(long sessionId) {
        new Thread(() -> {
            try {
                createSummaryWithRetry(sessionId, 1);
            } catch (Exception e) {
                log.error("Ошибка при асинхронном создании summary для сессии {}: {}", sessionId, e.getMessage());
            }
        }).start();
    }

    private void createSummaryWithRetry(long sessionId, int attempt) throws Exception {
        try {
            log.info("createSummaryWithRetry: sessionId={}, attempt={}", sessionId, attempt);

            Optional<GlobalSummaryDto> oldSummary = globalSummaryRepository.getLatestGlobalSummary(sessionId);

            int summaryInterval = sessionRepository.getCompressionSummaryInterval(sessionId);

            Long lastMessageId = oldSummary.map(GlobalSummaryDto::lastMessageId).orElse(0L);
            List<MessageDto> messagesToArchive = messageRepository.getMessagesAfterId(sessionId, lastMessageId, summaryInterval);

            log.info("createSummaryWithRetry: oldSummaryExists={}, messagesToArchive={}, lastMessageId={}",
                oldSummary.isPresent(), messagesToArchive.size(), lastMessageId);

            if (messagesToArchive.isEmpty()) {
                log.info("Нет новых сообщений для архивации");
                return;
            }

            log.info("createSummaryWithRetry: calling generateSummaryWithMetrics with oldSummaryExists={}, messagesCount={}",
                oldSummary.isPresent(), messagesToArchive.size());
            var result = summaryAgent.generateSummaryWithMetrics(oldSummary, messagesToArchive);
            String newSummaryContent = result.summary();

            int newVersion = globalSummaryRepository.getLatestVersion(sessionId) + 1;
            long newLastMessageId = messagesToArchive.get(messagesToArchive.size() - 1).id();

            globalSummaryRepository.saveGlobalSummary(
                sessionId,
                newSummaryContent,
                newVersion,
                newLastMessageId,
                result.inputTokens(),
                result.outputTokens(),
                result.totalTokens(),
                result.cost()
            );

            log.info("Summary успешно создан для сессии {}: version={}, lastMessageId={}, inputTokens={}, outputTokens={}, cost={}",
                sessionId, newVersion, newLastMessageId, result.inputTokens(), result.outputTokens(), result.cost());

        } catch (Exception e) {
            log.warn("Попытка {} создания summary для сессии {} завершилась с ошибкой: {}", attempt, sessionId, e.getMessage());

            if (attempt < MAX_RETRIES) {
                Thread.sleep(RETRY_DELAY_SECONDS * 1000);
                createSummaryWithRetry(sessionId, attempt + 1);
            } else {
                throw new Exception("Не удалось создать summary после " + MAX_RETRIES + " попыток", e);
            }
        }
    }
}
