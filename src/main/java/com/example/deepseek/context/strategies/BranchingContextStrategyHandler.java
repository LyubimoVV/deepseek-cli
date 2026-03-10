package com.example.deepseek.context.strategies;

import com.example.deepseek.context.ContextStrategyHandler;
import com.example.deepseek.db.BranchRepository;
import com.example.deepseek.db.BranchDto;
import com.example.deepseek.db.MessageRepository;
import com.example.deepseek.db.MessageDto;
import com.example.deepseek.dto.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class BranchingContextStrategyHandler implements ContextStrategyHandler {

    private static final Logger log = LoggerFactory.getLogger(BranchingContextStrategyHandler.class);

    private final MessageRepository messageRepository;
    private final BranchRepository branchRepository;

    public BranchingContextStrategyHandler(
        MessageRepository messageRepository,
        BranchRepository branchRepository
    ) {
        this.messageRepository = messageRepository;
        this.branchRepository = branchRepository;
    }

    @Override
    public List<Message> getContext(long sessionId, String systemMessage) {
        log.debug("BranchingStrategy: building context for sessionId={}", sessionId);

        List<Message> context = new ArrayList<>();
        context.add(Message.system(systemMessage));

        try {
            long branchIdFromDb = branchRepository.getActiveBranch(sessionId);
            long activeBranchId = branchIdFromDb == 0 ? 1L : branchIdFromDb;

            BranchDto activeBranch = branchRepository.getBranchById(activeBranchId)
                .orElseThrow(() -> new RuntimeException("Active branch not found: " + activeBranchId));

            log.debug("Building context for branch: id={}, name={}, parentMessageId={}", 
                     activeBranch.id(), activeBranch.name(), activeBranch.parentMessageId());

            if (activeBranch.parentMessageId() != null && activeBranchId != 1) {
                List<MessageDto> mainMessagesBeforeCheckpoint = messageRepository.getMessagesBeforeCheckpoint(
                    sessionId, 1L, activeBranch.parentMessageId()
                );

                for (MessageDto dto : mainMessagesBeforeCheckpoint) {
                    context.add(new Message(dto.role(), dto.content()));
                }

                log.debug("Loaded {} messages from main before checkpoint {}", 
                         mainMessagesBeforeCheckpoint.size(), activeBranch.parentMessageId());
            }

            List<MessageDto> branchMessages = messageRepository.getMessagesByBranch(sessionId, activeBranchId);

            for (MessageDto dto : branchMessages) {
                context.add(new Message(dto.role(), dto.content()));
            }

            log.debug("Loaded {} messages from active branch {}", branchMessages.size(), activeBranchId);

        } catch (java.sql.SQLException e) {
            log.error("Error building context for sessionId={}: {}", sessionId, e.getMessage());
        }

        log.info("Context strategy applied: BRANCHING, sessionId={}, messages in context={}", 
                 sessionId, context.size());

        return context;
    }

    @Override
    public void scheduleAfterMessageSave(long sessionId, int totalMessageCount) {
        log.debug("BranchingStrategy: scheduleAfterMessageSave called for sessionId={}, count={}", 
                 sessionId, totalMessageCount);
    }

    @Override
    public void validateParameters() {
        log.debug("BranchingStrategy: validateParameters called - always valid");
    }
}
