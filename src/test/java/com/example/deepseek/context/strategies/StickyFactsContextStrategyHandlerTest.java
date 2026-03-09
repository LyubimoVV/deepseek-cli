package com.example.deepseek.context.strategies;

import com.example.deepseek.db.FactDto;
import com.example.deepseek.db.FactsRepository;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StickyFactsContextStrategyHandlerTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private FactsRepository factsRepository;

    @InjectMocks
    private StickyFactsContextStrategyHandler handler;

    @Test
    void getContext_withFacts_returnsFactsAndMessages() throws SQLException {
        long sessionId = 1L;
        String systemMessage = "You are helpful";
        int windowSize = 5;

        List<FactDto> facts = Arrays.asList(
            new FactDto(1L, sessionId, "цель", "проект", "создать CLI", LocalDateTime.now()),
            new FactDto(2L, sessionId, "ограничения", "срок", "месяц", LocalDateTime.now())
        );

        List<MessageDto> messages = Arrays.asList(
            createMessage(10L, sessionId, "user", "Message 10"),
            createMessage(9L, sessionId, "assistant", "Message 9")
        );

        when(factsRepository.getFactsBySession(sessionId)).thenReturn(facts);
        when(sessionRepository.getStickyFactsWindowSize(sessionId)).thenReturn(windowSize);
        when(messageRepository.getMessagesForSlidingWindow(sessionId, windowSize)).thenReturn(messages);

        List<Message> context = handler.getContext(sessionId, systemMessage);

        assertThat(context).hasSizeGreaterThanOrEqualTo(3);
        assertThat(context.get(0).role()).isEqualTo("system");
        assertThat(context.get(0).content()).isEqualTo(systemMessage);

        verify(factsRepository).getFactsBySession(sessionId);
        verify(sessionRepository).getStickyFactsWindowSize(sessionId);
    }

    @Test
    void getContext_noFacts_returnsOnlyMessages() throws SQLException {
        long sessionId = 1L;
        String systemMessage = "You are helpful";
        int windowSize = 3;

        when(factsRepository.getFactsBySession(sessionId)).thenReturn(Collections.emptyList());
        when(sessionRepository.getStickyFactsWindowSize(sessionId)).thenReturn(windowSize);
        when(messageRepository.getMessagesForSlidingWindow(sessionId, windowSize))
            .thenReturn(Collections.emptyList());

        List<Message> context = handler.getContext(sessionId, systemMessage);

        assertThat(context).hasSize(1);
        assertThat(context.get(0).role()).isEqualTo("system");
    }

    @Test
    void getContext_emptySession_returnsOnlySystem() throws SQLException {
        long sessionId = 1L;
        String systemMessage = "You are helpful";

        when(factsRepository.getFactsBySession(sessionId)).thenReturn(Collections.emptyList());
        when(sessionRepository.getStickyFactsWindowSize(sessionId)).thenReturn(10);
        when(messageRepository.getMessagesForSlidingWindow(eq(sessionId), eq(10)))
            .thenReturn(Collections.emptyList());

        List<Message> context = handler.getContext(sessionId, systemMessage);

        assertThat(context).hasSize(1);
        assertThat(context.get(0).role()).isEqualTo("system");
        assertThat(context.get(0).content()).isEqualTo(systemMessage);
    }

    @Test
    void getContext_dbError_returnsSystemMessageOnly() throws SQLException {
        long sessionId = 1L;
        String systemMessage = "You are helpful";

        when(factsRepository.getFactsBySession(sessionId)).thenThrow(new SQLException("DB error"));

        List<Message> context = handler.getContext(sessionId, systemMessage);

        assertThat(context).hasSize(1);
        assertThat(context.get(0).role()).isEqualTo("system");
    }

    @Test
    void scheduleAfterMessageSave_doesNothing() {
        handler.scheduleAfterMessageSave(1L, 10);
        verifyNoInteractions(messageRepository, sessionRepository, factsRepository);
    }

    @Test
    void validateParameters_alwaysValid() {
        handler.validateParameters();
    }

    private MessageDto createMessage(long id, long sessionId, String role, String content) {
        return new MessageDto(id, sessionId, role, content, 0, 0, 0, 0, 0, 0.0, LocalDateTime.now());
    }
}
