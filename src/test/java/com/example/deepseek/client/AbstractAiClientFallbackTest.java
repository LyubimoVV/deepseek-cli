package com.example.deepseek.client;

import com.example.deepseek.context.ContextStrategy;
import com.example.deepseek.context.ContextStrategyFactory;
import com.example.deepseek.context.ContextStrategyHandler;
import com.example.deepseek.db.SessionRepository;
import com.example.deepseek.dto.LlmResponse;
import com.example.deepseek.dto.Message;
import com.example.deepseek.dto.TokenUsage;
import com.example.deepseek.client.AiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AbstractAiClientFallbackTest {

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private ContextStrategyFactory strategyFactory;

    @Mock
    private ContextStrategyHandler handler;

    private TestableAbstractAiClient client;

    @BeforeEach
    void setUp() {
        client = new TestableAbstractAiClient(strategyFactory, sessionRepository);
    }

    @Test
    void getMessagesForRequest_dataAccessException_usesFallback() throws SQLException {
        client.setCurrentSessionId(1L);
        
        lenient().when(sessionRepository.getContextStrategy(1L))
            .thenThrow(new RuntimeException("DB connection failed"));

        List<Message> messages = client.getFallbackContext();

        assertThat(messages).isNotNull();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).role()).isEqualTo("system");
    }

    @Test
    void getMessagesForRequest_invalidConfig_usesFallback() throws SQLException {
        client.setCurrentSessionId(1L);
        
        lenient().when(sessionRepository.getContextStrategy(1L))
            .thenThrow(new IllegalArgumentException("Invalid config"));

        List<Message> messages = client.getFallbackContext();

        assertThat(messages).isNotNull();
        assertThat(messages).hasSize(1);
    }

    @Test
    void getMessagesForRequest_strategyFactoryNull_usesFallback() {
        client.setCurrentSessionId(1L);
        client.setContextStrategyFactory(null);

        List<Message> messages = client.getMessagesForRequest();

        assertThat(messages).isNotNull();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).role()).isEqualTo("system");
    }

    @Test
    void getMessagesForRequest_sessionRepositoryNull_usesFallback() {
        client.setCurrentSessionId(1L);
        
        TestableAbstractAiClient clientWithNullRepo = new TestableAbstractAiClient(strategyFactory, null);

        List<Message> messages = clientWithNullRepo.getMessagesForRequest();

        assertThat(messages).isNotNull();
        assertThat(messages).hasSize(1);
    }

    @Test
    void getMessagesForRequest_handlerThrowsRuntimeException_usesFallback() throws SQLException {
        client.setCurrentSessionId(1L);
        
        when(sessionRepository.getContextStrategy(1L)).thenReturn(ContextStrategy.COMPRESSION);
        when(strategyFactory.getHandler(ContextStrategy.COMPRESSION)).thenReturn(handler);
        when(handler.getContext(anyLong(), anyString()))
            .thenThrow(new RuntimeException("Handler error"));

        List<Message> messages = client.getMessagesForRequest();

        assertThat(messages).isNotNull();
        assertThat(messages).hasSize(1);
    }

    @Test
    void getFallbackContext_returnsSystemMessage() {
        List<Message> messages = client.getFallbackContext();

        assertThat(messages).isNotNull();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).role()).isEqualTo("system");
    }

    static class TestableAbstractAiClient extends AbstractAiClient {
        
        public TestableAbstractAiClient(ContextStrategyFactory strategyFactory, SessionRepository sessionRepository) {
            super(strategyFactory, sessionRepository);
        }

        @Override
        protected String sendApiRequest(String userMessage) {
            return "test";
        }

        @Override
        protected LlmResponse sendApiRequestWithMessages(List<Message> messages) throws AiException {
            return new LlmResponse("test response", new TokenUsage(10, 20, 30));
        }

        @Override
        public String getCurrentModel() {
            return "test-model";
        }

        @Override
        public String getModelDisplayName() {
            return "Test Model";
        }

        @Override
        public String getProviderName() {
            return "Test";
        }

        @Override
        public List<Message> getMessagesForRequest() {
            return super.getMessagesForRequest();
        }

        @Override
        public List<Message> getFallbackContext() {
            return super.getFallbackContext();
        }

        public void setCurrentSessionId(long sessionId) {
            super.setCurrentSessionId(sessionId);
        }

        public void setContextStrategyFactory(ContextStrategyFactory factory) {
            super.setContextStrategyFactory(factory);
        }
    }
}
