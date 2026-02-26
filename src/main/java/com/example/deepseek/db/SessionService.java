package com.example.deepseek.db;

import com.example.deepseek.client.ClientManager;
import com.example.deepseek.dto.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final ExecutorService executor;

    private long currentSessionId = -1;

    public SessionService() {
        this.sessionRepository = new SessionRepository();
        this.messageRepository = new MessageRepository();
        this.executor = Executors.newCachedThreadPool();
    }

    public long createSession(String title, String model, String systemMessage, int mode) {
        try {
            long sessionId = sessionRepository.createSession(
                title != null ? title : "Новая сессия",
                model,
                systemMessage,
                mode
            );
            setActiveSession(sessionId);
            return sessionId;
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при создании сессии: " + e.getMessage(), e);
        }
    }

    public Optional<SessionDto> getSession(long id) {
        try {
            return sessionRepository.getSession(id);
        } catch (Exception e) {
            log.error("Ошибка при получении сессии: " + e.getMessage());
            return Optional.empty();
        }
    }

    public List<SessionDto> getAllSessions() {
        try {
            return sessionRepository.getAllSessions();
        } catch (Exception e) {
            log.error("Ошибка при получении списка сессий: " + e.getMessage());
            return List.of();
        }
    }

    public void deleteSession(long id) {
        try {
            // Сначала удаляем сообщения
            messageRepository.deleteMessagesBySession(id);
            // Потом удаляем сессию
            sessionRepository.deleteSession(id);
            if (currentSessionId == id) {
                currentSessionId = -1;
            }
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при удалении сессии: " + e.getMessage(), e);
        }
    }

    public List<MessageDto> getSessionMessages(long sessionId) {
        try {
            return messageRepository.getMessagesBySession(sessionId);
        } catch (Exception e) {
            log.error("Ошибка при получении сообщений: " + e.getMessage());
            return List.of();
        }
    }

    public void setActiveSession(long sessionId) {
        try {
            currentSessionId = sessionId;
            sessionRepository.setActiveSessionId(sessionId);
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при установке активной сессии: " + e.getMessage(), e);
        }
    }

    public Optional<SessionDto> getActiveSession() {
        if (currentSessionId <= 0) {
            return Optional.empty();
        }
        return getSession(currentSessionId);
    }

    public long getCurrentSessionId() {
        return currentSessionId;
    }

    public Optional<SessionDto> loadLastSession() {
        try {
            // 1. Пробуем загрузить активную сессию из app_state
            Optional<Long> activeId = sessionRepository.getActiveSessionId();
            if (activeId.isPresent()) {
                Optional<SessionDto> session = sessionRepository.getSession(activeId.get());
                if (session.isPresent()) {
                    currentSessionId = session.get().id();
                    // Если в этой сессии есть сообщения - используем её
                    if (session.get().messageCount() > 0) {
                        return session;
                    }
                    // Если активная сессия пустая - пробуем найти другую с сообщениями
                }
            }

            // 2. Ищем любую сессию с сообщениями
            List<SessionDto> sessions = sessionRepository.getAllSessions();
            for (SessionDto s : sessions) {
                if (s.messageCount() > 0) {
                    currentSessionId = s.id();
                    sessionRepository.setActiveSessionId(currentSessionId);
                    return Optional.of(s);
                }
            }

            // 3. Если есть хоть какая-то сессия - возвращаем первую
            if (!sessions.isEmpty()) {
                currentSessionId = sessions.get(0).id();
                sessionRepository.setActiveSessionId(currentSessionId);
                return Optional.of(sessions.get(0));
            }
            
            // 4. Нет сессий - возвращаем пустой
        } catch (Exception e) {
            log.error("Ошибка при загрузке последней сессии: " + e.getMessage());
        }
        return Optional.empty();
    }

    public void saveMessageAsync(String role, String content, int inputTokens, int outputTokens, int totalTokens, int cachedTokens, int latency, double cost) {
        if (currentSessionId <= 0) {
            return;
        }

        long sessionId = currentSessionId;
        executor.submit(() -> {
            try {
                messageRepository.saveMessage(sessionId, role, content, inputTokens, outputTokens, totalTokens, cachedTokens, latency, cost);
                sessionRepository.updateSessionTimestamp(sessionId);
                
                // Update session stats only for assistant messages (responses)
                if ("assistant".equals(role)) {
                    sessionRepository.updateSessionStats(sessionId, totalTokens, cost);
                }
            } catch (Exception e) {
                log.error("Ошибка при сохранении сообщения: " + e.getMessage());
            }
        });
    }

    public void saveMessage(String role, String content, int inputTokens, int outputTokens, int totalTokens, int cachedTokens, int latency, double cost) {
        if (currentSessionId <= 0) {
            return;
        }

        try {
            messageRepository.saveMessage(currentSessionId, role, content, inputTokens, outputTokens, totalTokens, cachedTokens, latency, cost);
            sessionRepository.updateSessionTimestamp(currentSessionId);
            
            // Update session stats only for assistant messages (responses)
            if ("assistant".equals(role)) {
                sessionRepository.updateSessionStats(currentSessionId, totalTokens, cost);
            }
        } catch (Exception e) {
            log.error("Ошибка при сохранении сообщения: " + e.getMessage());
        }
    }

    public void updateSessionTitleAsync(String newTitle) {
        if (currentSessionId <= 0) {
            return;
        }

        long sessionId = currentSessionId;
        executor.submit(() -> {
            try {
                sessionRepository.updateSessionTitle(sessionId, newTitle);
            } catch (Exception e) {
                log.error("Ошибка при обновлении названия сессии: " + e.getMessage());
            }
        });
    }

    public void generateTitleFromFirstMessage() {
        if (currentSessionId <= 0) {
            return;
        }

        long sessionId = currentSessionId;
        executor.submit(() -> {
            try {
                String firstMessage = messageRepository.getFirstUserMessage(sessionId);
                if (firstMessage != null && !firstMessage.isBlank()) {
                    String[] words = firstMessage.trim().split("\\s+");
                    int wordCount = Math.min(words.length, 5);
                    StringBuilder title = new StringBuilder();
                    for (int i = 0; i < wordCount; i++) {
                        if (i > 0) title.append(" ");
                        title.append(words[i]);
                    }
                    if (words.length > 5) {
                        title.append("...");
                    }
                    sessionRepository.updateSessionTitle(sessionId, title.toString());
                }
            } catch (Exception e) {
                log.error("Ошибка при генерации названия сессии: " + e.getMessage());
            }
        });
    }

    public void restoreSessionToClient(ClientManager clientManager) {
        if (currentSessionId <= 0) {
            log.info("restoreSessionToClient: currentSessionId = " + currentSessionId);
            return;
        }

        try {
            List<MessageDto> messages = messageRepository.getMessagesBySession(currentSessionId);
            log.info("restoreSessionToClient: загружено " + messages.size() + " сообщений");
            
            if (messages.isEmpty()) {
                return;
            }
            
            // Сохраняем системное сообщение из БД
            String systemMessageFromDb = null;
            List<Message> userAssistantMessages = new ArrayList<>();
            
            for (MessageDto msg : messages) {
                if ("system".equals(msg.role())) {
                    systemMessageFromDb = msg.content();
                } else {
                    userAssistantMessages.add(new Message(msg.role(), msg.content()));
                }
            }
            
            // Если в БД нет system message - используем текущий из clientManager
            if (systemMessageFromDb == null) {
                systemMessageFromDb = clientManager.getSystemMessage();
            }
            
            log.info("restoreSessionToClient: system=" + (systemMessageFromDb != null ? "да" : "нет") + ", user/assistant=" + userAssistantMessages.size());
            
            // Очищаем историю (сбрасывает к системному сообщению по умолчанию)
            clientManager.clearAllHistory();
            log.info("restoreSessionToClient: история очищена");
            
            // Если есть сохраненное system message, обновляем его вручную (без сброса истории)
            if (systemMessageFromDb != null) {
                clientManager.getCurrentClient().getConversationHistoryForRestore().set(0, Message.system(systemMessageFromDb));
            }
            
            // Добавляем все user/assistant сообщения
            for (Message msg : userAssistantMessages) {
                clientManager.getCurrentClient().getConversationHistoryForRestore().add(msg);
            }
            
            log.info("restoreSessionToClient: восстановлено " + clientManager.getCurrentClient().getConversationHistory().size() + " сообщений в истории");
            
        } catch (Exception e) {
            log.error("Ошибка при восстановлении сессии: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void updateSessionModel(String model) {
        if (currentSessionId <= 0) {
            return;
        }

        try {
            sessionRepository.updateSessionModel(currentSessionId, model);
        } catch (Exception e) {
            log.error("Ошибка при обновлении модели сессии: " + e.getMessage());
        }
    }

    public SessionRepository.SessionStats getSessionStats(long sessionId) {
        try {
            return sessionRepository.getSessionStats(sessionId);
        } catch (Exception e) {
            log.error("Ошибка при получении статистики сессии: " + e.getMessage());
            return new SessionRepository.SessionStats(0, 0.0, 0);
        }
    }

    public SessionRepository.SessionStats getCurrentSessionStats() {
        return getSessionStats(currentSessionId);
    }

    public void shutdown() {
        executor.shutdown();
    }
}
