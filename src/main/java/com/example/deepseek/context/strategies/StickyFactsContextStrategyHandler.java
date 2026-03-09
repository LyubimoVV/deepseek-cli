package com.example.deepseek.context.strategies;

import com.example.deepseek.context.ContextStrategyHandler;
import com.example.deepseek.db.FactsRepository;
import com.example.deepseek.db.MessageRepository;
import com.example.deepseek.db.SessionRepository;
import com.example.deepseek.dto.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class StickyFactsContextStrategyHandler implements ContextStrategyHandler {

    private static final Logger log = LoggerFactory.getLogger(StickyFactsContextStrategyHandler.class);

    private final MessageRepository messageRepository;
    private final SessionRepository sessionRepository;
    private final FactsRepository factsRepository;

    public StickyFactsContextStrategyHandler(
        MessageRepository messageRepository,
        SessionRepository sessionRepository,
        FactsRepository factsRepository
    ) {
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.factsRepository = factsRepository;
    }

    @Override
    public List<Message> getContext(long sessionId, String systemMessage) {
        log.debug("StickyFactsStrategy: building context for sessionId={}", sessionId);

        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemMessage));

        try {
            var facts = factsRepository.getFactsBySession(sessionId);
            if (!facts.isEmpty()) {
                StringBuilder factsContent = new StringBuilder("Сохранённые факты:\n");
                String currentCategory = null;
                for (var fact : facts) {
                    if (!fact.category().equals(currentCategory)) {
                        currentCategory = fact.category();
                        factsContent.append("\n## ").append(currentCategory).append(":\n");
                    }
                    factsContent.append("- ").append(fact.key()).append(": ").append(fact.value()).append("\n");
                }
                messages.add(Message.system(factsContent.toString()));
            }

            int windowSize = sessionRepository.getStickyFactsWindowSize(sessionId);
            log.debug("StickyFactsStrategy: windowSize={} for sessionId={}", windowSize, sessionId);

            var messageDtos = messageRepository.getMessagesForSlidingWindow(sessionId, windowSize);

            for (int i = messageDtos.size() - 1; i >= 0; i--) {
                var dto = messageDtos.get(i);
                messages.add(new Message(dto.role(), dto.content()));
            }
        } catch (java.sql.SQLException e) {
            log.error("Error building context for sessionId={}: {}", sessionId, e.getMessage());
        }

        log.info("Context strategy applied: STICKY_FACTS, sessionId={}, messages in context={}", 
            sessionId, messages.size());

        return messages;
    }

    @Override
    public void scheduleAfterMessageSave(long sessionId, int totalMessageCount) {
        log.debug("StickyFactsStrategy: scheduleAfterMessageSave called for sessionId={}, count={}", 
            sessionId, totalMessageCount);
    }

    @Override
    public void validateParameters() throws IllegalArgumentException {
        log.debug("StickyFactsStrategy: validateParameters called");
    }
}
