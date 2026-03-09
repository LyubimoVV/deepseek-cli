package com.example.deepseek.context;

import com.example.deepseek.dto.Message;
import java.util.List;

public interface ContextStrategyHandler {
    List<Message> getContext(long sessionId, String systemMessage);
    
    void scheduleAfterMessageSave(long sessionId, int totalMessageCount);
    
    void validateParameters() throws IllegalArgumentException;
}
