package com.example.deepseek.context.strategies;

import com.example.deepseek.db.MessageDto;
import com.example.deepseek.db.MessageRepository;
import com.example.deepseek.dto.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NoneContextStrategyHandlerTest {

    @Mock
    private MessageRepository messageRepository;

    @InjectMocks
    private NoneContextStrategyHandler handler;

    @Test
    void getContext_returnsAllMessages() throws SQLException {
        long sessionId = 1L;
        String systemMessage = "You are helpful";
        
        List<MessageDto> messages = Arrays.asList(
            createMessage(1L, sessionId, "user", "Hello"),
            createMessage(2L, sessionId, "assistant", "Hi there"),
            createMessage(3L, sessionId, "user", "How are you?")
        );
        
        lenient().when(messageRepository.getAllMessagesForSession(sessionId)).thenReturn(messages);

        List<Message> context = handler.getContext(sessionId, systemMessage);

        assertThat(context).hasSize(4);
        assertThat(context.get(0).role()).isEqualTo("system");
        assertThat(context.get(0).content()).isEqualTo(systemMessage);
        assertThat(context.get(1).role()).isEqualTo("user");
        assertThat(context.get(1).content()).isEqualTo("Hello");
        
        verify(messageRepository).getAllMessagesForSession(sessionId);
    }

    @Test
    void getContext_emptySession_returnsOnlySystemMessage() throws SQLException {
        long sessionId = 1L;
        String systemMessage = "You are helpful";
        
        when(messageRepository.getAllMessagesForSession(sessionId)).thenAnswer(invocation -> Collections.emptyList());

        List<Message> context = handler.getContext(sessionId, systemMessage);

        assertThat(context).hasSize(1);
        assertThat(context.get(0).role()).isEqualTo("system");
        
        verify(messageRepository).getAllMessagesForSession(sessionId);
    }

    @Test
    void scheduleAfterMessageSave_doesNothing() {
        handler.scheduleAfterMessageSave(1L, 10);
        verifyNoInteractions(messageRepository);
    }

    @Test
    void validateParameters_alwaysValid() {
        assertThatCode(() -> handler.validateParameters())
            .doesNotThrowAnyException();
    }

    private MessageDto createMessage(long id, long sessionId, String role, String content) {
        return new MessageDto(id, sessionId, role, content, 0, 0, 0, 0, 0, 0.0, LocalDateTime.now());
    }
}
