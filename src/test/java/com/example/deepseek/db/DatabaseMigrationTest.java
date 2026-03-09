package com.example.deepseek.db;

import com.example.deepseek.context.ContextStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseMigrationTest {

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DatabaseConfig.getConnection();
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
    void migration_addsContextStrategyColumn() throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        
        try (ResultSet rs = metaData.getColumns(null, null, "sessions", "context_strategy")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("COLUMN_NAME")).isEqualTo("context_strategy");
        }
    }

    @Test
    void migration_addsWindowSizeColumn() throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        
        try (ResultSet rs = metaData.getColumns(null, null, "sessions", "sliding_window_size")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("COLUMN_NAME")).isEqualTo("sliding_window_size");
            assertThat(rs.getString("TYPE_NAME")).isEqualTo("INTEGER");
        }
    }

    @Test
    void newSession_hasDefaultValues() throws Exception {
        SessionRepository sessionRepository = new SessionRepository();
        long sessionId = sessionRepository.createSession("Test", "gpt-4", "Helpful", 2);

        var session = sessionRepository.getSession(sessionId);

        assertThat(session).isPresent();
        assertThat(session.get().contextStrategy()).isEqualTo(ContextStrategy.NONE);
        assertThat(session.get().slidingWindowSize()).isEqualTo(10);
        assertThat(session.get().stickyFactsWindowSize()).isEqualTo(10);
        assertThat(session.get().compressionKeepMessages()).isEqualTo(3);
        assertThat(session.get().compressionSummaryInterval()).isEqualTo(10);
    }
}
