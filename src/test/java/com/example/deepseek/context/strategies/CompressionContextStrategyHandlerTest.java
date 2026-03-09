package com.example.deepseek.context.strategies;

import com.example.deepseek.agent.SummaryAgent;
import com.example.deepseek.context.ContextScheduler;
import com.example.deepseek.db.GlobalSummaryDto;
import com.example.deepseek.db.GlobalSummaryRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompressionContextStrategyHandlerTest {

    @Mock
    private ContextScheduler contextScheduler;

    @Mock
    private SummaryAgent summaryAgent;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private GlobalSummaryRepository globalSummaryRepository;

    @Mock
    private SessionRepository sessionRepository;

    @InjectMocks
    private CompressionContextStrategyHandler handler;

    @Test
    void getContext_withSummary_returnsHybridContext() throws SQLException {
        long sessionId = 1L;
        String systemMessage = "You are helpful";
        
        SessionRepository.SessionContextSettings settings = 
            new SessionRepository.SessionContextSettings(3, 5, true);
        
        GlobalSummaryDto summary = new GlobalSummaryDto(
            sessionId, "Summary content", 1, 100L,
            LocalDateTime.now(), 100, 200, 300, 0.01
        );
        
        List<MessageDto> messages = Arrays.asList(
            createMessage(101L, sessionId, "user", "Message 101"),
            createMessage(102L, sessionId, "assistant", "Message 102"),
            createMessage(103L, sessionId, "user", "Message 103")
        );
        
        lenient().when(sessionRepository.getContextSettings(sessionId)).thenReturn(settings);
        when(globalSummaryRepository.getLatestGlobalSummary(sessionId))
            .thenReturn(Optional.of(summary));
        when(messageRepository.getMessagesAfterMessageId(sessionId, 100L, 3))
            .thenReturn(messages);

        List<Message> context = handler.getContext(sessionId, systemMessage);

        assertThat(context).hasSize(5);
        assertThat(context.get(0).role()).isEqualTo("system");
        assertThat(context.get(1).role()).isEqualTo("system");
        assertThat(context.get(1).content()).contains("Контекст диалога");
    }

    @Test
    void getContext_withoutSummary_returnsOnlyRecent() throws SQLException {
        long sessionId = 1L;
        String systemMessage = "You are helpful";
        
        SessionRepository.SessionContextSettings settings = 
            new SessionRepository.SessionContextSettings(5, 10, false);
        
        List<MessageDto> messages = Arrays.asList(
            createMessage(1L, sessionId, "user", "Message 1"),
            createMessage(2L, sessionId, "assistant", "Message 2")
        );
        
        lenient().when(sessionRepository.getContextSettings(sessionId)).thenReturn(settings);
        lenient().when(globalSummaryRepository.getLatestGlobalSummary(sessionId))
            .thenReturn(Optional.empty());
        lenient().when(messageRepository.getRecentMessagesForSession(sessionId, 5))
            .thenReturn(messages);

        List<Message> context = handler.getContext(sessionId, systemMessage);

        assertThat(context).hasSize(3);
        assertThat(context.get(0).role()).isEqualTo("system");
    }

    @Test
    void getContext_summaryDisabled_returnsRecentMessagesOnly() throws SQLException {
        long sessionId = 1L;
        String systemMessage = "You are helpful";
        
        SessionRepository.SessionContextSettings settings = 
            new SessionRepository.SessionContextSettings(3, 5, false);
        
        List<MessageDto> messages = Arrays.asList(
            createMessage(1L, sessionId, "user", "Message 1"),
            createMessage(2L, sessionId, "assistant", "Message 2")
        );
        
        lenient().when(sessionRepository.getContextSettings(sessionId)).thenReturn(settings);
        when(messageRepository.getRecentMessagesForSession(sessionId, 3))
            .thenReturn(messages);

        List<Message> context = handler.getContext(sessionId, systemMessage);

        assertThat(context).hasSize(3);
        verify(globalSummaryRepository, never()).getLatestGlobalSummary(anyLong());
    }

    @Test
    void getContext_invalidKeepMessagesCount_returnsSystemMessageOnly() throws SQLException {
        long sessionId = 1L;
        
        SessionRepository.SessionContextSettings settings = 
            new SessionRepository.SessionContextSettings(-1, 10, true);
        
        lenient().when(sessionRepository.getContextSettings(sessionId)).thenReturn(settings);

        List<Message> context = handler.getContext(sessionId, "System");

        assertThat(context).hasSize(1);
        assertThat(context.get(0).role()).isEqualTo("system");
    }

    @Test
    void getContext_invalidSummaryInterval_returnsSystemMessageOnly() throws SQLException {
        long sessionId = 1L;
        
        SessionRepository.SessionContextSettings settings = 
            new SessionRepository.SessionContextSettings(5, 0, true);
        
        lenient().when(sessionRepository.getContextSettings(sessionId)).thenReturn(settings);

        List<Message> context = handler.getContext(sessionId, "System");

        assertThat(context).hasSize(1);
        assertThat(context.get(0).role()).isEqualTo("system");
    }

    @Test
    void scheduleAfterMessageSave_delegatesToScheduler() throws SQLException {
        long sessionId = 1L;
        int messageCount = 15;
        
        SessionRepository.SessionContextSettings settings = 
            new SessionRepository.SessionContextSettings(5, 10, true);
        
        lenient().when(sessionRepository.getContextSettings(sessionId)).thenReturn(settings);

        handler.scheduleAfterMessageSave(sessionId, messageCount);

        verify(contextScheduler).scheduleAfterMessageSave(sessionId, messageCount);
    }

    @Test
    void scheduleAfterMessageSave_summaryDisabled_skips() throws SQLException {
        long sessionId = 1L;
        
        SessionRepository.SessionContextSettings settings = 
            new SessionRepository.SessionContextSettings(5, 10, false);
        
        lenient().when(sessionRepository.getContextSettings(sessionId)).thenReturn(settings);

        handler.scheduleAfterMessageSave(sessionId, 10);

        verify(contextScheduler).scheduleAfterMessageSave(sessionId, 10);
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
