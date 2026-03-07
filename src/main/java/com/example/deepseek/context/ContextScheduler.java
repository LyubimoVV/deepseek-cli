package com.example.deepseek.context;

import com.example.deepseek.agent.SummaryAgent;
import com.example.deepseek.db.GlobalSummaryDto;
import com.example.deepseek.db.GlobalSummaryRepository;
import com.example.deepseek.db.MessageDto;
import com.example.deepseek.db.MessageRepository;
import com.example.deepseek.db.SessionRepository;
import com.example.deepseek.db.SessionRepository.SessionContextSettings;
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
            SessionContextSettings settings = sessionRepository.getContextSettings(sessionId);
            
            if (!settings.summaryEnabled()) {
                log.info("shouldCreateSummary: summary disabled for sessionId={}", sessionId);
                return false;
            }
            
            int summaryBufferSize = settings.keepMessagesCount() + settings.summaryInterval();
            
            Optional<GlobalSummaryDto> existingSummary = globalSummaryRepository.getGlobalSummary(sessionId);
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

            Optional<GlobalSummaryDto> oldSummary = globalSummaryRepository.getGlobalSummary(sessionId);

            SessionContextSettings settings = sessionRepository.getContextSettings(sessionId);
            int summaryInterval = settings.summaryInterval();

            Long lastMessageId = oldSummary.map(GlobalSummaryDto::lastMessageId).orElse(0L);
            List<MessageDto> messagesToArchive = messageRepository.getMessagesAfterId(sessionId, lastMessageId, summaryInterval);

            log.info("createSummaryWithRetry: oldSummaryExists={}, messagesToArchive={}, lastMessageId={}",
                oldSummary.isPresent(), messagesToArchive.size(), lastMessageId);

            if (messagesToArchive.isEmpty()) {
                log.info("Нет новых сообщений для архивации");
                return;
            }

            log.info("createSummaryWithRetry: calling generateSummaryFromMessages with oldSummaryExists={}, messagesCount={}",
                oldSummary.isPresent(), messagesToArchive.size());
            String newSummaryContent = summaryAgent.generateSummaryFromMessages(oldSummary, messagesToArchive);

            int newVersion = oldSummary.map(s -> s.version() + 1).orElse(1);
            long newLastMessageId = messagesToArchive.get(messagesToArchive.size() - 1).id();

            globalSummaryRepository.saveGlobalSummary(sessionId, newSummaryContent, newVersion, newLastMessageId);

            log.info("Summary успешно создан для сессии {}: version={}, lastMessageId={}", sessionId, newVersion, newLastMessageId);

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
