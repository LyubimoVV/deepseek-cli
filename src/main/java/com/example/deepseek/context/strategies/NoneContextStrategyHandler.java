package com.example.deepseek.context.strategies;

import com.example.deepseek.context.ContextStrategyHandler;
import com.example.deepseek.db.MessageRepository;
import com.example.deepseek.dto.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NoneContextStrategyHandler implements ContextStrategyHandler {

    private static final Logger log = LoggerFactory.getLogger(NoneContextStrategyHandler.class);

    private final MessageRepository messageRepository;

    public NoneContextStrategyHandler(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Override
    public List<Message> getContext(long sessionId, String systemMessage) {
        log.debug("NoneContextStrategy: loading all messages for sessionId={}", sessionId);

        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemMessage));

        try {
            var messageDtos = messageRepository.getAllMessagesForSession(sessionId);
            
            for (var dto : messageDtos) {
                messages.add(new Message(dto.role(), dto.content()));
            }
        } catch (java.sql.SQLException e) {
            log.error("Error loading messages for sessionId={}: {}", sessionId, e.getMessage());
        }

        log.info("Context strategy applied: NONE, sessionId={}, messages in context={}", 
            sessionId, messages.size());

        return messages;
    }

    @Override
    public void scheduleAfterMessageSave(long sessionId, int totalMessageCount) {
        log.debug("NoneContextStrategy: scheduleAfterMessageSave called for sessionId={}, count={}", 
            sessionId, totalMessageCount);
    }

    @Override
    public void validateParameters() {
        log.debug("NoneContextStrategy: validateParameters called - always valid");
    }
}
