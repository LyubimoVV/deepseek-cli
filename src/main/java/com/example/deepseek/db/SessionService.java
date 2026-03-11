package com.example.deepseek.db;

import com.example.deepseek.agent.FactsExtractionAgent;
import com.example.deepseek.agent.SummaryAgent;
import com.example.deepseek.client.ClientManager;
import com.example.deepseek.context.ContextScheduler;
import com.example.deepseek.context.ContextStrategy;
import com.example.deepseek.context.ContextStrategyFactory;
import com.example.deepseek.context.ContextStrategyHandler;
import com.example.deepseek.dto.Message;
import com.example.deepseek.memory.agent.MemoryExtractionAgent;
import com.example.deepseek.memory.MemoryScope;
import com.example.deepseek.memory.dto.MemorySuggestion;
import com.example.deepseek.db.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final ExecutorService executor;

    private final ScheduledExecutorService suggestionScheduler;
    private final Map<Long, ScheduledFuture<?>> pendingSuggestions;
    private final Map<Long, List<MemorySuggestion>> suggestionCache;
    private static final long SUGGEST_DEBOUNCE_MS = 30_000;

    private long currentSessionId = -1;

    private SummaryAgent summaryAgent;
    private ContextScheduler contextScheduler;
    private ContextStrategyFactory strategyFactory;
    private FactsRepository factsRepository;
    private FactsExtractionAgent factsExtractionAgent;
    private BranchRepository branchRepository;
    private MemoryExtractionAgent memoryExtractionAgent;

    public SessionService() {
        this.sessionRepository = new SessionRepository();
        this.messageRepository = new MessageRepository();
        this.executor = Executors.newCachedThreadPool();
        this.suggestionScheduler = Executors.newSingleThreadScheduledExecutor();
        this.pendingSuggestions = new ConcurrentHashMap<>();
        this.suggestionCache = new ConcurrentHashMap<>();
    }

    public long createSession(String title, String model, String systemMessage, int mode) {
        return createSession(title, model, systemMessage, mode, -1);
    }

    public long createSession(String title, String model, String systemMessage, int mode, long profileId) {
        try {
            long effectiveProfileId = profileId;
            if (profileId <= 0) {
                effectiveProfileId = getProfileIdFromCurrentSession();
            }

            long sessionId = sessionRepository.createSession(
                title != null ? title : "Новая сессия",
                model,
                systemMessage,
                mode,
                effectiveProfileId
            );
            setActiveSession(sessionId);
            return sessionId;
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при создании сессии: " + e.getMessage(), e);
        }
    }

    private long getProfileIdFromCurrentSession() {
        if (currentSessionId > 0) {
            try {
                return sessionRepository.getProfileId(currentSessionId);
            } catch (Exception e) {
                log.warn("Failed to get profileId from current session: {}", e.getMessage());
            }
        }
        return 1L;
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
            // Сначала удаляем все ветки (это удалит ссылки на parent_message_id)
            branchRepository.deleteBySession(id);
            // Потом удаляем запись из app_state для активной ветки
            branchRepository.deleteActiveBranchState(id);
            // Потом удаляем сессию (cascade удалит сообщения и другие связанные записи)
            sessionRepository.deleteSession(id);
            // Очищаем кэш активной ветки для этой сессии
            sessionRepository.clearActiveBranchCache(id);
            if (currentSessionId == id) {
                currentSessionId = -1;
            }
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при удалении сессии: " + e.getMessage(), e);
        }
    }

    public List<MessageDto> getSessionMessages(long sessionId) {
        try {
            ContextStrategy strategy = getContextStrategy(sessionId);

            if (strategy == ContextStrategy.BRANCHING) {
                try {
                    Long activeBranchId = sessionRepository.getActiveBranchId(sessionId);
                    BranchDto branch = branchRepository.getBranchById(activeBranchId).orElse(null);

                    if (branch == null) {
                        log.warn("Ветка не найдена: branchId={}, sessionId={}", activeBranchId, sessionId);
                        return messageRepository.getMessagesByBranch(sessionId, activeBranchId);
                    }

                    List<MessageDto> result = new ArrayList<>();

                    if (branch.parentMessageId() != null) {
                        Long mainBranchId = branchRepository.getMainBranchId(sessionId);
                        if (mainBranchId != null) {
                            List<MessageDto> mainMessagesBeforeCheckpoint = messageRepository.getMessagesBeforeCheckpoint(
                                sessionId, mainBranchId, branch.parentMessageId()
                            );
                            result.addAll(mainMessagesBeforeCheckpoint);
                        }
                    }

                    List<MessageDto> branchMessages = messageRepository.getMessagesByBranch(sessionId, activeBranchId);
                    result.addAll(branchMessages);

                    log.info("Загружены сообщения для ветки {}: branchId={}, mainBefore={}, branch={}, total={}",
                        branch.name(), activeBranchId,
                        branch.parentMessageId() != null ? result.size() - branchMessages.size() : 0,
                        branchMessages.size(), result.size());

                    return result;
                } catch (Exception e) {
                    log.error("Ошибка при загрузке сообщений ветки: {}", e.getMessage());
                    return messageRepository.getMessagesBySession(sessionId);
                }
            }

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

    public void setSummaryAgent(SummaryAgent summaryAgent) {
        this.summaryAgent = summaryAgent;
    }

    public void setContextScheduler(ContextScheduler contextScheduler) {
        this.contextScheduler = contextScheduler;
    }

    public void setStrategyFactory(ContextStrategyFactory strategyFactory) {
        this.strategyFactory = strategyFactory;
    }

    public void setFactsRepository(FactsRepository factsRepository) {
        this.factsRepository = factsRepository;
    }

    public void setFactsExtractionAgent(FactsExtractionAgent factsExtractionAgent) {
        this.factsExtractionAgent = factsExtractionAgent;
    }

    public void setBranchRepository(BranchRepository branchRepository) {
        this.branchRepository = branchRepository;
        sessionRepository.setBranchRepository(branchRepository);
    }

    public BranchDto createBranch(long sessionId, String name, Long checkpointMessageId) {
        try {
            if (name == null || name.isBlank() || name.length() > 50) {
                throw new IllegalArgumentException("Branch name must be 1-50 characters");
            }

            if (checkpointMessageId != null) {
                if (!messageRepository.existsInSession(checkpointMessageId, sessionId)) {
                    throw new IllegalArgumentException("Checkpoint message not found in session");
                }
            }

            if (branchRepository.countBySession(sessionId) >= 10) {
                throw new IllegalStateException("Maximum 10 branches per session");
            }

            return branchRepository.createBranch(sessionId, name, checkpointMessageId);
        } catch (Exception e) {
            log.error("Ошибка при создании ветки: {}", e.getMessage());
            throw new RuntimeException("Ошибка при создании ветки: " + e.getMessage(), e);
        }
    }

    public List<BranchDto> getBranches(long sessionId) {
        try {
            return branchRepository.getBranchesBySession(sessionId);
        } catch (Exception e) {
            log.error("Ошибка при получении списка веток: {}", e.getMessage());
            return List.of();
        }
    }

    public BranchDto getActiveBranch(long sessionId) {
        try {
            long activeBranchId = sessionRepository.getActiveBranchId(sessionId);
            return branchRepository.getBranchById(activeBranchId).orElse(null);
        } catch (Exception e) {
            log.error("Ошибка при получении активной ветки: {}", e.getMessage());
            return null;
        }
    }

    public void switchBranch(long sessionId, long branchId) {
        try {
            if (!branchRepository.belongsToSession(branchId, sessionId)) {
                throw new IllegalArgumentException("Branch does not belong to session");
            }

            sessionRepository.setActiveBranchId(sessionId, branchId);
            log.info("Переключение ветки: sessionId={}, branchId={}", sessionId, branchId);
        } catch (Exception e) {
            log.error("Ошибка при переключении ветки: {}", e.getMessage());
            throw new RuntimeException("Ошибка при переключении ветки: " + e.getMessage(), e);
        }
    }

    public void deleteBranch(long branchId) {
        try {
            BranchDto branch = branchRepository.getBranchById(branchId)
                .orElseThrow(() -> new IllegalArgumentException("Branch not found"));

            if (branch.isMain()) {
                throw new IllegalStateException("Cannot delete main branch. Create a new main first.");
            }

            Long activeBranchId = sessionRepository.getActiveBranchId(branch.sessionId());
            if (activeBranchId == branchId) {
                Long mainBranchId = branchRepository.getMainBranchId(branch.sessionId());
                if (mainBranchId != null) {
                    sessionRepository.setActiveBranchId(branch.sessionId(), mainBranchId);
                }
            }

            branchRepository.deleteBranch(branchId);
            log.info("Ветка удалена: id={}", branchId);
        } catch (Exception e) {
            log.error("Ошибка при удалении ветки: {}", e.getMessage());
            throw new RuntimeException("Ошибка при удалении ветки: " + e.getMessage(), e);
        }
    }

    public void initializeBranchingStrategy(long sessionId) {
        try {
            int messageCount = messageRepository.getMessageCountBySession(sessionId);
            sessionRepository.initializeBranching(sessionId, messageCount);

            Long mainBranchId = branchRepository.getMainBranchId(sessionId);
            if (mainBranchId != null) {
                messageRepository.updateBranchIdForSession(sessionId, mainBranchId);
            }
            log.info("Инициализация branching стратегии для сессии {} завершена", sessionId);
        } catch (Exception e) {
            log.error("Ошибка при инициализации branching стратегии: {}", e.getMessage());
            throw new RuntimeException("Ошибка при инициализации branching стратегии: " + e.getMessage(), e);
        }
    }

    public void updateCompressionSettings(long sessionId, int keepMessages, int summaryInterval) {
        try {
            sessionRepository.updateCompressionSettings(sessionId, keepMessages, summaryInterval);
            log.info("Настройки Compression обновлены для сессии {}: keepMessages={}, summaryInterval={}",
                sessionId, keepMessages, summaryInterval);
        } catch (Exception e) {
            log.error("Ошибка при обновлении настроек Compression: " + e.getMessage());
            throw new RuntimeException("Ошибка при обновлении настроек Compression: " + e.getMessage(), e);
        }
    }

    public void updateContextSettings(long sessionId, int keepMessagesCount, int summaryInterval, int summaryBufferSize) {
        updateCompressionSettings(sessionId, keepMessagesCount, summaryInterval);
    }

    public ContextStrategy getContextStrategy(long sessionId) {
        try {
            return sessionRepository.getContextStrategy(sessionId);
        } catch (Exception e) {
            log.error("Ошибка при получении стратегии контекста: " + e.getMessage());
            return ContextStrategy.NONE;
        }
    }

    public void updateContextStrategy(long sessionId, ContextStrategy strategy) {
        try {
            sessionRepository.updateContextStrategy(sessionId, strategy);
            log.info("Стратегия контекста обновлена для сессии {}: {}", sessionId, strategy);
        } catch (Exception e) {
            log.error("Ошибка при обновлении стратегии контекста: " + e.getMessage());
            throw new RuntimeException("Ошибка при обновлении стратегии контекста: " + e.getMessage(), e);
        }
    }

    public int getStickyFactsWindowSize(long sessionId) {
        try {
            return sessionRepository.getStickyFactsWindowSize(sessionId);
        } catch (Exception e) {
            log.error("Ошибка при получении stickyFactsWindowSize: " + e.getMessage());
            return 10;
        }
    }

    public void updateStickyFactsWindowSize(long sessionId, int windowSize) {
        try {
            if (windowSize < 1 || windowSize > 100) {
                throw new IllegalArgumentException("stickyFactsWindowSize must be between 1 and 100");
            }
            sessionRepository.updateStickyFactsWindowSize(sessionId, windowSize);
            log.info("stickyFactsWindowSize обновлён для сессии {}: {}", sessionId, windowSize);
        } catch (Exception e) {
            log.error("Ошибка при обновлении stickyFactsWindowSize: " + e.getMessage());
            throw new RuntimeException("Ошибка при обновлении stickyFactsWindowSize: " + e.getMessage(), e);
        }
    }

    public int getSlidingWindowSize(long sessionId) {
        try {
            return sessionRepository.getSlidingWindowSize(sessionId);
        } catch (Exception e) {
            log.error("Ошибка при получении slidingWindowSize: " + e.getMessage());
            return 10;
        }
    }

    public void updateSlidingWindowSize(long sessionId, int windowSize) {
        try {
            if (windowSize < 1 || windowSize > 100) {
                throw new IllegalArgumentException("slidingWindowSize must be between 1 and 100");
            }
            sessionRepository.updateSlidingWindowSize(sessionId, windowSize);
            log.info("slidingWindowSize обновлён для сессии {}: {}", sessionId, windowSize);
        } catch (Exception e) {
            log.error("Ошибка при обновлении slidingWindowSize: " + e.getMessage());
            throw new RuntimeException("Ошибка при обновлении slidingWindowSize: " + e.getMessage(), e);
        }
    }

    public int getCompressionKeepMessages(long sessionId) {
        try {
            return sessionRepository.getCompressionKeepMessages(sessionId);
        } catch (Exception e) {
            log.error("Ошибка при получении compressionKeepMessages: " + e.getMessage());
            return 3;
        }
    }

    public void updateCompressionKeepMessages(long sessionId, int keepMessages) {
        try {
            sessionRepository.updateCompressionKeepMessages(sessionId, keepMessages);
            log.info("compressionKeepMessages обновлён для сессии {}: {}", sessionId, keepMessages);
        } catch (Exception e) {
            log.error("Ошибка при обновлении compressionKeepMessages: " + e.getMessage());
            throw new RuntimeException("Ошибка при обновлении compressionKeepMessages: " + e.getMessage(), e);
        }
    }

    public int getCompressionSummaryInterval(long sessionId) {
        try {
            return sessionRepository.getCompressionSummaryInterval(sessionId);
        } catch (Exception e) {
            log.error("Ошибка при получении compressionSummaryInterval: " + e.getMessage());
            return 10;
        }
    }

    public void updateCompressionSummaryInterval(long sessionId, int summaryInterval) {
        try {
            sessionRepository.updateCompressionSummaryInterval(sessionId, summaryInterval);
            log.info("compressionSummaryInterval обновлён для сессии {}: {}", sessionId, summaryInterval);
        } catch (Exception e) {
            log.error("Ошибка при обновлении compressionSummaryInterval: " + e.getMessage());
            throw new RuntimeException("Ошибка при обновлении compressionSummaryInterval: " + e.getMessage(), e);
        }
    }

    // Facts management methods
    public List<FactDto> getFacts(long sessionId) {
        try {
            if (factsRepository == null) {
                factsRepository = new FactsRepository();
            }
            return factsRepository.getFactsBySession(sessionId);
        } catch (Exception e) {
            log.error("Ошибка при получении фактов: " + e.getMessage());
            return List.of();
        }
    }

    public FactDto saveFact(long sessionId, String category, String key, String value) {
        try {
            if (factsRepository == null) {
                factsRepository = new FactsRepository();
            }
            long id = factsRepository.saveFact(sessionId, category, key, value);
            return new FactDto(id, sessionId, category, key, value, java.time.LocalDateTime.now());
        } catch (Exception e) {
            log.error("Ошибка при сохранении факта: " + e.getMessage());
            throw new RuntimeException("Ошибка при сохранении факта: " + e.getMessage(), e);
        }
    }

    public FactDto updateFact(long factId, String category, String key, String value) {
        try {
            if (factsRepository == null) {
                factsRepository = new FactsRepository();
            }
            var existingFact = factsRepository.getFactById(factId);
            if (existingFact.isEmpty()) {
                throw new IllegalArgumentException("Fact not found: " + factId);
            }
            factsRepository.updateFact(factId, category, key, value);
            return new FactDto(factId, existingFact.get().sessionId(), category, key, value, java.time.LocalDateTime.now());
        } catch (Exception e) {
            log.error("Ошибка при обновлении факта: " + e.getMessage());
            throw new RuntimeException("Ошибка при обновлении факта: " + e.getMessage(), e);
        }
    }

    public void deleteFact(long factId) {
        try {
            if (factsRepository == null) {
                factsRepository = new FactsRepository();
            }
            factsRepository.deleteFact(factId);
        } catch (Exception e) {
            log.error("Ошибка при удалении факта: " + e.getMessage());
            throw new RuntimeException("Ошибка при удалении факта: " + e.getMessage(), e);
        }
    }

    public void extractFactsFromLastMessage(long sessionId) {
        if (factsExtractionAgent != null) {
            factsExtractionAgent.extractFactsFromLastMessages(sessionId);
        }
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
                        log.info("Загружена активная сессия: id={}, title={}, messages={}", 
                            session.get().id(), session.get().title(), session.get().messageCount());
                        return session;
                    }
                    // Если активная сессия пустая - пробуем найти другую с сообщениями
                    log.info("Активная сессия {} пустая, ищем другую с сообщениями", activeId.get());
                }
            }

            // 2. Ищем любую сессию с сообщениями
            List<SessionDto> sessions = sessionRepository.getAllSessions();
            for (SessionDto s : sessions) {
                if (s.messageCount() > 0) {
                    currentSessionId = s.id();
                    sessionRepository.setActiveSessionId(currentSessionId);
                    log.info("Загружена сессия с сообщениями: id={}, title={}, messages={}", 
                        s.id(), s.title(), s.messageCount());
                    return Optional.of(s);
                }
            }

            // 3. Если есть хоть какая-то сессия - возвращаем первую
            if (!sessions.isEmpty()) {
                currentSessionId = sessions.get(0).id();
                sessionRepository.setActiveSessionId(currentSessionId);
                log.info("Загружена первая сессия (пустая): id={}, title={}", 
                    sessions.get(0).id(), sessions.get(0).title());
                return Optional.of(sessions.get(0));
            }
            
            log.info("Нет сохраненных сессий");
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
        Long branchId = null;
        try {
            branchId = sessionRepository.getActiveBranchId(sessionId);
        } catch (Exception e) {
            log.warn("Ошибка при получении активной ветки: {}", e.getMessage());
        }
        final Long finalBranchId = branchId;

        executor.submit(() -> {
            try {
                messageRepository.saveMessage(sessionId, role, content, inputTokens, outputTokens, totalTokens, cachedTokens, latency, cost, finalBranchId);
                sessionRepository.updateSessionTimestamp(sessionId);

                // Update session stats only for assistant messages (responses)
                if ("assistant".equals(role)) {
                    sessionRepository.updateSessionStats(sessionId, totalTokens, cost);
                }

                // Вызываем scheduleAfterMessageSave через стратегию
                if (strategyFactory != null) {
                    int messageCount = messageRepository.getMessageCountBySession(sessionId);
                    ContextStrategy strategy = sessionRepository.getContextStrategy(sessionId);
                    ContextStrategyHandler handler = strategyFactory.getHandler(strategy);
                    handler.scheduleAfterMessageSave(sessionId, messageCount);
                }
            } catch (Exception e) {
                log.error("Ошибка при сохранении сообщения: " + e.getMessage());
            }
        });
    }

    public long saveMessage(String role, String content, int inputTokens, int outputTokens, int totalTokens, int cachedTokens, int latency, double cost) {
        if (currentSessionId <= 0) {
            return 0;
        }

        try {
            Long branchId = sessionRepository.getActiveBranchId(currentSessionId);
            long messageId = messageRepository.saveMessage(currentSessionId, role, content, inputTokens, outputTokens, totalTokens, cachedTokens, latency, cost, branchId);
            sessionRepository.updateSessionTimestamp(currentSessionId);

            // Update session stats only for assistant messages (responses)
            if ("assistant".equals(role)) {
                sessionRepository.updateSessionStats(currentSessionId, totalTokens, cost);
            }

            // Проверяем, нужно ли создать summary (для COMPRESSION стратегии)
            ContextStrategy strategy = sessionRepository.getContextStrategy(currentSessionId);
            if (contextScheduler != null && strategy == ContextStrategy.COMPRESSION) {
                int messageCount = messageRepository.getMessageCountBySession(currentSessionId);
                contextScheduler.scheduleAfterMessageSave(currentSessionId, messageCount);
            }

            // Извлекаем факты для STICKY_FACTS стратегии после сообщения пользователя
            if (factsExtractionAgent != null && strategy == ContextStrategy.STICKY_FACTS && "user".equals(role)) {
                factsExtractionAgent.extractFactsFromUserMessage(currentSessionId, content);
            }

            return messageId;
        } catch (Exception e) {
            log.error("Ошибка при сохранении сообщения: " + e.getMessage());
            return 0;
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

    public void restoreSessionToClient(ClientManager clientManager, SummaryAgent summaryAgent) {
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

    public SessionRepository.SessionStats getBranchStats(long sessionId, long branchId) {
        try {
            return messageRepository.getBranchStats(sessionId, branchId);
        } catch (Exception e) {
            log.error("Ошибка при получении статистики ветки: " + e.getMessage());
            return new SessionRepository.SessionStats(0, 0.0, 0);
        }
    }

    public void shutdown() {
        executor.shutdown();
        suggestionScheduler.shutdown();
    }

    public void onMessageSaved(long sessionId, String role, String content) {
        if (!"user".equals(role)) {
            return;
        }

        var previous = pendingSuggestions.get(sessionId);
        if (previous != null) {
            previous.cancel(false);
        }

        var future = suggestionScheduler.schedule(() -> {
            try {
                var scope = new MemoryScope(sessionId, getSessionProfileId(sessionId));
                var suggestions = memoryExtractionAgent.analyze(content, scope);
                if (!suggestions.isEmpty()) {
                    suggestionCache.put(sessionId, suggestions);
                    log.debug("Generated {} suggestions for session {}", suggestions.size(), sessionId);
                }
            } catch (Exception e) {
                log.warn("Failed to generate suggestions for session {}", sessionId, e);
            } finally {
                pendingSuggestions.remove(sessionId);
            }
        }, SUGGEST_DEBOUNCE_MS, TimeUnit.MILLISECONDS);

        pendingSuggestions.put(sessionId, future);
    }

    public List<MemorySuggestion> getSuggestions(long sessionId) {
        return suggestionCache.getOrDefault(sessionId, List.of());
    }

    public void markSuggestionsAsViewed(long sessionId) {
        suggestionCache.remove(sessionId);
    }

    public void setMemoryExtractionAgent(MemoryExtractionAgent agent) {
        this.memoryExtractionAgent = agent;
    }

    private long getSessionProfileId(long sessionId) {
        try {
            return sessionRepository.getProfileId(sessionId);
        } catch (Exception e) {
            log.warn("Failed to get profileId for session {}: {}", sessionId, e.getMessage());
            return 1L;
        }
    }

    public void updateSessionProfile(long sessionId, long profileId, String systemPrompt) {
        try {
            sessionRepository.updateSessionProfile(sessionId, profileId, systemPrompt);
            log.info("Session profile updated: sessionId={}, profileId={}", sessionId, profileId);
        } catch (SQLException e) {
            log.error("Error updating session profile: {}", e.getMessage());
        }
    }

    public String getSystemMessage(long sessionId) {
        try {
            return sessionRepository.getSystemMessage(sessionId);
        } catch (Exception e) {
            log.error("Error getting system message for session {}: {}", sessionId, e.getMessage());
            return "";
        }
    }

    public SessionRepository getSessionRepository() {
        return sessionRepository;
    }

    public MessageRepository getMessageRepository() {
        return messageRepository;
    }
}
