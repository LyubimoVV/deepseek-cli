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
        
        try (ResultSet rs = metaData.getColumns(null, null, "sessions", "window_size")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("COLUMN_NAME")).isEqualTo("window_size");
            assertThat(rs.getString("TYPE_NAME")).isEqualTo("INTEGER");
        }
    }

    @Test
    void migration_existingSessionWithSummaryEnabled_hasCompressionStrategy() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                INSERT INTO sessions (title, model, system_message, mode, summary_enabled)
                VALUES ('Old session', 'gpt-4', 'Helpful', 2, 1)
                """);
            
            try (ResultSet rs = stmt.executeQuery("SELECT context_strategy FROM sessions WHERE title = 'Old session'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("context_strategy")).isEqualTo("COMPRESSION");
            }
        }
    }

    @Test
    void migration_existingSessionWithSummaryDisabled_hasNoneStrategy() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                INSERT INTO sessions (title, model, system_message, mode, summary_enabled)
                VALUES ('Old session 2', 'gpt-4', 'Helpful', 2, 0)
                """);
            
            try (ResultSet rs = stmt.executeQuery("SELECT context_strategy FROM sessions WHERE title = 'Old session 2'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("context_strategy")).isEqualTo("COMPRESSION");
            }
        }
    }

    @Test
    void newSession_hasDefaultValues() throws Exception {
        SessionRepository sessionRepository = new SessionRepository();
        long sessionId = sessionRepository.createSession("Test", "gpt-4", "Helpful", 2);
        
        var session = sessionRepository.getSession(sessionId);
        
        assertThat(session).isPresent();
        assertThat(session.get().contextStrategy()).isEqualTo(ContextStrategy.COMPRESSION);
        assertThat(session.get().windowSize()).isEqualTo(10);
    }
}
