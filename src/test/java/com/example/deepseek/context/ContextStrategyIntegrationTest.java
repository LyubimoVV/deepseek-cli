package com.example.deepseek.context;

import com.example.deepseek.context.strategies.CompressionContextStrategyHandler;
import com.example.deepseek.context.strategies.NoneContextStrategyHandler;
import com.example.deepseek.context.strategies.SlidingWindowContextStrategyHandler;
import com.example.deepseek.db.DatabaseConfig;
import com.example.deepseek.db.MessageRepository;
import com.example.deepseek.db.SessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContextStrategyIntegrationTest {

    private Connection connection;
    private SessionRepository sessionRepository;
    private MessageRepository messageRepository;
    private ContextStrategyFactory strategyFactory;

    @BeforeEach
    void setUp() throws Exception {
        connection = DatabaseConfig.getConnection();
        sessionRepository = new SessionRepository();
        messageRepository = new MessageRepository();
        
        NoneContextStrategyHandler noneHandler = new NoneContextStrategyHandler(messageRepository);
        CompressionContextStrategyHandler compressionHandler = new CompressionContextStrategyHandler(
            null, null, messageRepository, null, sessionRepository
        );
        SlidingWindowContextStrategyHandler slidingHandler = new SlidingWindowContextStrategyHandler(
            messageRepository, sessionRepository
        );
        
        strategyFactory = new ContextStrategyFactory(noneHandler, compressionHandler, slidingHandler);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (connection != null && !connection.isClosed()) {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("DELETE FROM messages");
                    stmt.execute("DELETE FROM sessions");
                    stmt.execute("DELETE FROM global_summaries");
                }
            }
        } catch (Exception e) {
        }
    }

    @Test
    void noneStrategy_returnsAllMessages() throws Exception {
        long sessionId = sessionRepository.createSession("Test", "gpt-4", "You are helpful", 2);
        
        for (int i = 0; i < 15; i++) {
            messageRepository.saveMessage(sessionId, "user", "Message " + i, 0, 0, 0, 0, 0, 0.0);
            messageRepository.saveMessage(sessionId, "assistant", "Response " + i, 0, 0, 0, 0, 0, 0.0);
        }

        var handler = strategyFactory.getHandler(ContextStrategy.NONE);
        var context = handler.getContext(sessionId, "System");

        assertThat(context).hasSize(31);
    }

    @Test
    void slidingWindow_returnsLimitedMessages() throws Exception {
        long sessionId = sessionRepository.createSession("Test", "gpt-4", "You are helpful", 2);
        
        for (int i = 0; i < 20; i++) {
            messageRepository.saveMessage(sessionId, "user", "Message " + i, 0, 0, 0, 0, 0, 0.0);
        }
        
        sessionRepository.updateWindowSize(sessionId, 7);

        var handler = strategyFactory.getHandler(ContextStrategy.SLIDING_WINDOW);
        var context = handler.getContext(sessionId, "System");

        assertThat(context).hasSize(8);
    }

    @Test
    void switchingStrategy_updatesContext() throws Exception {
        long sessionId = sessionRepository.createSession("Test", "gpt-4", "Helpful", 2);
        
        for (int i = 0; i < 10; i++) {
            messageRepository.saveMessage(sessionId, "user", "Message " + i, 0, 0, 0, 0, 0, 0.0);
            messageRepository.saveMessage(sessionId, "assistant", "Response " + i, 0, 0, 0, 0, 0, 0.0);
        }

        sessionRepository.updateContextStrategy(sessionId, ContextStrategy.NONE);
        var noneHandler = strategyFactory.getHandler(ContextStrategy.NONE);
        var noneContext = noneHandler.getContext(sessionId, "System");
        assertThat(noneContext).hasSize(21);

        sessionRepository.updateContextStrategy(sessionId, ContextStrategy.SLIDING_WINDOW);
        sessionRepository.updateWindowSize(sessionId, 5);
        var slidingHandler = strategyFactory.getHandler(ContextStrategy.SLIDING_WINDOW);
        var slidingContext = slidingHandler.getContext(sessionId, "System");
        assertThat(slidingContext).hasSize(6);
    }

    @Test
    void newSession_hasDefaultStrategy() throws Exception {
        long sessionId = sessionRepository.createSession("New Session", "gpt-4", "Helpful", 2);

        ContextStrategy strategy = sessionRepository.getContextStrategy(sessionId);
        
        assertThat(strategy).isEqualTo(ContextStrategy.COMPRESSION);
    }
}
