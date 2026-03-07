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
            SessionRepository.SessionContextSettings settings = sessionRepository.getContextSettings(sessionId);
            int interval = settings.summaryInterval();
            
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
            SessionRepository.SessionContextSettings settings = sessionRepository.getContextSettings(sessionId);
            return settings.keepMessagesCount();
        } catch (Exception e) {
            log.error("Ошибка при получении keepMessagesCount для сессии {}: {}", sessionId, e.getMessage());
            return 3;
        }
    }

    public int getSummaryInterval(long sessionId) {
        try {
            SessionRepository.SessionContextSettings settings = sessionRepository.getContextSettings(sessionId);
            return settings.summaryInterval();
        } catch (Exception e) {
            log.error("Ошибка при получении summaryInterval для сессии {}: {}", sessionId, e.getMessage());
            return 3;
        }
    }

    public void updateContextSettings(long sessionId, int keepMessagesCount, int summaryInterval, int summaryBufferSize) {
        try {
            sessionRepository.updateContextSettings(sessionId, keepMessagesCount, summaryInterval);
            log.info("Настройки контекста обновлены для сессии {}: keepMessagesCount={}, summaryInterval={}, summaryBufferSize={}",
                sessionId, keepMessagesCount, summaryInterval, summaryBufferSize);
        } catch (Exception e) {
            log.error("Ошибка при обновлении настроек контекста для сессии {}: {}", sessionId, e.getMessage());
            throw new RuntimeException("Ошибка при обновлении настроек контекста: " + e.getMessage(), e);
        }
    }
}
