package com.example.deepseek.memory.repository;

import com.example.deepseek.db.SessionRepository;
import com.example.deepseek.memory.dto.WorkingMemoryDto;
import com.example.deepseek.memory.repository.impl.WorkingMemoryRepositoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WorkingMemoryRepositoryTest {

    private WorkingMemoryRepository repo;
    private SessionRepository sessionRepository;

    private long sessionId1;
    private long sessionId2;

    @BeforeEach
    void setUp() throws Exception {
        repo = new WorkingMemoryRepositoryImpl();
        sessionRepository = new SessionRepository();

        sessionId1 = sessionRepository.createSession("Test Session 1", "deepseek-chat", "You are helpful", 2, 1L);
        sessionId2 = sessionRepository.createSession("Test Session 2", "deepseek-chat", "You are helpful", 2, 1L);
    }

    @AfterEach
    void tearDown() throws SQLException {
        var conn = com.example.deepseek.db.DatabaseConfig.getConnection();
        try (var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM working_memory");
            stmt.execute("DELETE FROM sessions WHERE id IN (" + sessionId1 + ", " + sessionId2 + ")");
        }
    }

    @Test
    void save_and_retrieve() throws Exception {
        long id = repo.save(sessionId1, "task", "current_goal", "реализовать auth", 1);

        assertThat(id).isPositive();

        Optional<WorkingMemoryDto> result = repo.getById(id);
        assertThat(result).isPresent();
        assertThat(result.get().sessionId()).isEqualTo(sessionId1);
        assertThat(result.get().category()).isEqualTo("task");
        assertThat(result.get().key()).isEqualTo("current_goal");
        assertThat(result.get().value()).isEqualTo("реализовать auth");
    }

    @Test
    void save_updates_on_duplicate_key() throws Exception {
        repo.save(sessionId1, "task", "goal", "value1", 1);

        long id2 = repo.save(sessionId1, "task", "goal", "value2", 2);

        Optional<WorkingMemoryDto> result = repo.getByKey(sessionId1, "goal");
        assertThat(result).isPresent();
        assertThat(result.get().value()).isEqualTo("value2");
        assertThat(result.get().priority()).isEqualTo(2);
    }

    @Test
    void deleteByKey() throws Exception {
        repo.save(sessionId1, "task", "goal", "value", 1);

        repo.delete(sessionId1, "goal");

        Optional<WorkingMemoryDto> result = repo.getByKey(sessionId1, "goal");
        assertThat(result).isEmpty();
    }

    @Test
    void deleteAllForSession() throws Exception {
        repo.save(sessionId1, "task", "goal1", "value1", 1);
        repo.save(sessionId1, "task", "goal2", "value2", 1);
        repo.save(sessionId2, "task", "goal3", "value3", 1);

        repo.deleteAllForSession(sessionId1);

        List<WorkingMemoryDto> session1 = repo.getBySession(sessionId1);
        List<WorkingMemoryDto> session2 = repo.getBySession(sessionId2);

        assertThat(session1).isEmpty();
        assertThat(session2).hasSize(1);
    }

    @Test
    void getByCategory() throws Exception {
        repo.save(sessionId1, "task", "goal1", "value1", 1);
        repo.save(sessionId1, "task", "goal2", "value2", 1);
        repo.save(sessionId1, "prefs", "theme", "dark", 1);

        List<WorkingMemoryDto> items = repo.getBySessionAndCategory(sessionId1, "task");

        assertThat(items).hasSize(2);
        assertThat(items).allMatch(item -> item.category().equals("task"));
    }

    @Test
    void getBySessionAndKeys() throws Exception {
        repo.save(sessionId1, "task", "goal1", "value1", 1);
        repo.save(sessionId1, "task", "goal2", "value2", 1);
        repo.save(sessionId1, "task", "goal3", "value3", 1);

        List<WorkingMemoryDto> items = repo.getBySessionAndKeys(sessionId1, java.util.Set.of("goal1", "goal2"));

        assertThat(items).hasSize(2);
        assertThat(items).anyMatch(item -> item.key().equals("goal1"));
        assertThat(items).anyMatch(item -> item.key().equals("goal2"));
        assertThat(items).noneMatch(item -> item.key().equals("goal3"));
    }

    @Test
    void update() throws Exception {
        long id = repo.save(sessionId1, "task", "goal", "value1", 1);

        repo.update(id, "prefs", "goal", "value2", 2);

        Optional<WorkingMemoryDto> result = repo.getById(id);
        assertThat(result).isPresent();
        assertThat(result.get().category()).isEqualTo("prefs");
        assertThat(result.get().value()).isEqualTo("value2");
        assertThat(result.get().priority()).isEqualTo(2);
    }
}
