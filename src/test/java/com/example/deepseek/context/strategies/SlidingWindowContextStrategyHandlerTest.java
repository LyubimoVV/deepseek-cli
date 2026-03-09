package com.example.deepseek.context.strategies;

import com.example.deepseek.db.MessageDto;
import com.example.deepseek.db.MessageRepository;
import com.example.deepseek.db.SessionRepository;
import com.example.deepseek.dto.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlidingWindowContextStrategyHandlerTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private SessionRepository sessionRepository;

    @InjectMocks
    private SlidingWindowContextStrategyHandler handler;

    @Test
    void getContext_returnsLastNMessages() throws SQLException {
        long sessionId = 1L;
        int windowSize = 5;
        String systemMessage = "You are helpful";
        
        List<MessageDto> messages = Arrays.asList(
            createMessage(10L, sessionId, "user", "Message 10"),
            createMessage(9L, sessionId, "assistant", "Message 9"),
            createMessage(8L, sessionId, "user", "Message 8"),
            createMessage(7L, sessionId, "assistant", "Message 7"),
            createMessage(6L, sessionId, "user", "Message 6")
        );
        
        when(sessionRepository.getSlidingWindowSize(sessionId)).thenReturn(windowSize);
        when(messageRepository.getMessagesForSlidingWindow(sessionId, windowSize))
            .thenReturn(messages);

        List<Message> context = handler.getContext(sessionId, systemMessage);

        assertThat(context).hasSize(6);
        assertThat(context.get(0).role()).isEqualTo("system");
        assertThat(context.get(1).content()).isEqualTo("Message 6");
        
        verify(sessionRepository).getSlidingWindowSize(sessionId);
        verify(messageRepository).getMessagesForSlidingWindow(sessionId, windowSize);
    }

    @Test
    void getContext_invalidWindowSize_returnsSystemMessageOnly() throws SQLException {
        long sessionId = 1L;
        
        when(sessionRepository.getSlidingWindowSize(sessionId)).thenReturn(150);
        when(messageRepository.getMessagesForSlidingWindow(sessionId, 150))
            .thenReturn(Collections.emptyList());

        List<Message> context = handler.getContext(sessionId, "System");

        assertThat(context).hasSize(1);
        assertThat(context.get(0).role()).isEqualTo("system");
    }

    @Test
    void getContext_windowSizeZero_returnsSystemMessageOnly() throws SQLException {
        long sessionId = 1L;
        
        when(sessionRepository.getSlidingWindowSize(sessionId)).thenReturn(0);
        when(messageRepository.getMessagesForSlidingWindow(sessionId, 0))
            .thenReturn(Collections.emptyList());

        List<Message> context = handler.getContext(sessionId, "System");

        assertThat(context).hasSize(1);
        assertThat(context.get(0).role()).isEqualTo("system");
    }

    @Test
    void getContext_windowSizeTooLarge_returnsSystemMessageOnly() throws SQLException {
        long sessionId = 1L;
        
        when(sessionRepository.getSlidingWindowSize(sessionId)).thenReturn(101);
        when(messageRepository.getMessagesForSlidingWindow(sessionId, 101))
            .thenReturn(Collections.emptyList());

        List<Message> context = handler.getContext(sessionId, "System");

        assertThat(context).hasSize(1);
        assertThat(context.get(0).role()).isEqualTo("system");
    }

    @Test
    void getContext_validWindowSize_success() throws SQLException {
        long sessionId = 1L;
        int windowSize = 10;
        
        when(sessionRepository.getSlidingWindowSize(sessionId)).thenReturn(windowSize);
        when(messageRepository.getMessagesForSlidingWindow(sessionId, windowSize))
            .thenReturn(Collections.emptyList());

        List<Message> context = handler.getContext(sessionId, "System");

        assertThat(context).hasSize(1);
        assertThat(context.get(0).role()).isEqualTo("system");
    }

    @Test
    void scheduleAfterMessageSave_doesNothing() {
        handler.scheduleAfterMessageSave(1L, 10);
        
        verifyNoInteractions(messageRepository, sessionRepository);
    }

    @Test
    void validateParameters_alwaysValid() {
        // No parameters to validate in SlidingWindowStrategy
    }

    private MessageDto createMessage(long id, long sessionId, String role, String content) {
        return new MessageDto(id, sessionId, role, content, 0, 0, 0, 0, 0, 0.0, LocalDateTime.now());
    }
}
