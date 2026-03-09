package com.example.deepseek.context.strategies;

import com.example.deepseek.agent.SummaryAgent;
import com.example.deepseek.context.ContextScheduler;
import com.example.deepseek.context.ContextStrategyHandler;
import com.example.deepseek.db.GlobalSummaryRepository;
import com.example.deepseek.db.MessageRepository;
import com.example.deepseek.db.SessionRepository;
import com.example.deepseek.db.GlobalSummaryDto;
import com.example.deepseek.dto.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class CompressionContextStrategyHandler implements ContextStrategyHandler {

    private static final Logger log = LoggerFactory.getLogger(CompressionContextStrategyHandler.class);

    private final ContextScheduler contextScheduler;
    private final SummaryAgent summaryAgent;
    private final MessageRepository messageRepository;
    private final GlobalSummaryRepository globalSummaryRepository;
    private final SessionRepository sessionRepository;

    public CompressionContextStrategyHandler(
        ContextScheduler contextScheduler,
        SummaryAgent summaryAgent,
        MessageRepository messageRepository,
        GlobalSummaryRepository globalSummaryRepository,
        SessionRepository sessionRepository
    ) {
        this.contextScheduler = contextScheduler;
        this.summaryAgent = summaryAgent;
        this.messageRepository = messageRepository;
        this.globalSummaryRepository = globalSummaryRepository;
        this.sessionRepository = sessionRepository;
    }

    @Override
    public List<Message> getContext(long sessionId, String systemMessage) {
        log.debug("CompressionStrategy: building context for sessionId={}", sessionId);

        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemMessage));

        try {
            var settings = sessionRepository.getContextSettings(sessionId);
            int keepMessagesCount = settings.keepMessagesCount();
            boolean summaryEnabled = settings.summaryEnabled();

            if (!summaryEnabled) {
                log.debug("CompressionStrategy: summary disabled, using recent messages only");
                var recentMessages = messageRepository.getRecentMessagesForSession(sessionId, keepMessagesCount);
                for (var dto : recentMessages) {
                    messages.add(new Message(dto.role(), dto.content()));
                }
            } else {
                var summaryOpt = globalSummaryRepository.getLatestGlobalSummary(sessionId);
                
                if (summaryOpt.isPresent()) {
                    var summary = summaryOpt.get();
                    messages.add(Message.system("Контекст диалога: " + summary.content()));
                }

                long afterMessageId = summaryOpt.map(s -> s.lastMessageId()).orElse(0L);
                var recentMessages = messageRepository.getMessagesAfterMessageId(sessionId, afterMessageId, keepMessagesCount);
                
                for (var dto : recentMessages) {
                    messages.add(new Message(dto.role(), dto.content()));
                }
            }
        } catch (java.sql.SQLException e) {
            log.error("Error building context for sessionId={}: {}", sessionId, e.getMessage());
        }

        log.info("Context strategy applied: COMPRESSION, sessionId={}, messages in context={}", 
            sessionId, messages.size());

        return messages;
    }

    @Override
    public void scheduleAfterMessageSave(long sessionId, int totalMessageCount) {
        log.debug("CompressionStrategy: scheduleAfterMessageSave called for sessionId={}, count={}", 
            sessionId, totalMessageCount);
        contextScheduler.scheduleAfterMessageSave(sessionId, totalMessageCount);
    }

    @Override
    public void validateParameters() throws IllegalArgumentException {
        log.debug("CompressionStrategy: validateParameters called");
    }
}
