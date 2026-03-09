package com.example.deepseek.context.strategies;

import com.example.deepseek.context.ContextStrategyHandler;
import com.example.deepseek.db.MessageRepository;
import com.example.deepseek.db.SessionRepository;
import com.example.deepseek.dto.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class SlidingWindowContextStrategyHandler implements ContextStrategyHandler {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowContextStrategyHandler.class);

    private final MessageRepository messageRepository;
    private final SessionRepository sessionRepository;

    public SlidingWindowContextStrategyHandler(
        MessageRepository messageRepository,
        SessionRepository sessionRepository
    ) {
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
    }

    @Override
    public List<Message> getContext(long sessionId, String systemMessage) {
        log.debug("SlidingWindowStrategy: loading messages for sessionId={}", sessionId);

        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemMessage));

        try {
            int windowSize = sessionRepository.getWindowSize(sessionId);
            log.debug("SlidingWindowStrategy: windowSize={} for sessionId={}", windowSize, sessionId);

            var messageDtos = messageRepository.getMessagesForSlidingWindow(sessionId, windowSize);

            for (int i = messageDtos.size() - 1; i >= 0; i--) {
                var dto = messageDtos.get(i);
                messages.add(new Message(dto.role(), dto.content()));
            }
        } catch (java.sql.SQLException e) {
            log.error("Error loading messages for sessionId={}: {}", sessionId, e.getMessage());
        }

        log.info("Context strategy applied: SLIDING_WINDOW, sessionId={}, messages in context={}", 
            sessionId, messages.size());

        return messages;
    }

    @Override
    public void scheduleAfterMessageSave(long sessionId, int totalMessageCount) {
        log.debug("SlidingWindowStrategy: scheduleAfterMessageSave called for sessionId={}, count={}", 
            sessionId, totalMessageCount);
    }

    @Override
    public void validateParameters() throws IllegalArgumentException {
        log.debug("SlidingWindowStrategy: validateParameters called");
    }
}
