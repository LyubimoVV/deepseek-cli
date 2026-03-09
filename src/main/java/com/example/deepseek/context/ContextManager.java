package com.example.deepseek.context;

import com.example.deepseek.db.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    private final SessionRepository sessionRepository;

    public ContextManager(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    public boolean shouldCreateSummary(long sessionId, int messageCount) {
        try {
            int interval = sessionRepository.getCompressionSummaryInterval(sessionId);
            
            if (interval <= 0) {
                return false;
            }
            
            boolean shouldCreate = messageCount > 0 && messageCount % interval == 0;
            
            if (shouldCreate) {
                log.info("Пора создавать summary для сессии {}: {} сообщений (интервал: {})", 
                    sessionId, messageCount, interval);
            }
            
            return shouldCreate;
        } catch (Exception e) {
            log.error("Ошибка при проверке необходимости создания summary для сессии {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    public int getKeepMessagesCount(long sessionId) {
        try {
            return sessionRepository.getCompressionKeepMessages(sessionId);
        } catch (Exception e) {
            log.error("Ошибка при получении compressionKeepMessages для сессии {}: {}", sessionId, e.getMessage());
            return 3;
        }
    }

    public int getSummaryInterval(long sessionId) {
        try {
            return sessionRepository.getCompressionSummaryInterval(sessionId);
        } catch (Exception e) {
            log.error("Ошибка при получении compressionSummaryInterval для сессии {}: {}", sessionId, e.getMessage());
            return 3;
        }
    }

    public void updateContextSettings(long sessionId, int keepMessagesCount, int summaryInterval, int summaryBufferSize) {
        try {
            sessionRepository.updateCompressionSettings(sessionId, keepMessagesCount, summaryInterval);
            log.info("Настройки контекста обновлены для сессии {}: compressionKeepMessages={}, compressionSummaryInterval={}, summaryBufferSize={}",
                sessionId, keepMessagesCount, summaryInterval, summaryBufferSize);
        } catch (Exception e) {
            log.error("Ошибка при обновлении настроек контекста для сессии {}: {}", sessionId, e.getMessage());
            throw new RuntimeException("Ошибка при обновлении настроек контекста: " + e.getMessage(), e);
        }
    }
}
