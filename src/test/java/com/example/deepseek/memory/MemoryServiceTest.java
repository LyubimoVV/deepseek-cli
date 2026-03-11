package com.example.deepseek.memory;

import com.example.deepseek.db.DatabaseConfig;
import com.example.deepseek.db.SessionRepository;
import com.example.deepseek.memory.dto.LongTermMemoryDto;
import com.example.deepseek.memory.dto.WorkingMemoryDto;
import com.example.deepseek.memory.repository.impl.LongTermMemoryRepositoryImpl;
import com.example.deepseek.memory.repository.impl.WorkingMemoryRepositoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryServiceTest {

    private WorkingMemoryRepositoryImpl workingRepo;
    private LongTermMemoryRepositoryImpl longTermRepo;
    private SessionRepository sessionRepository;
    private MemoryService memoryService;

    private long sessionId1;
    private long sessionId2;

    @BeforeEach
    void setUp() throws Exception {
        workingRepo = new WorkingMemoryRepositoryImpl();
        longTermRepo = new LongTermMemoryRepositoryImpl();
        sessionRepository = new SessionRepository();
        memoryService = new MemoryService(workingRepo, longTermRepo, sessionRepository);

        sessionId1 = sessionRepository.createSession("Test Session 1", "deepseek-chat", "You are helpful", 2);
        sessionId2 = sessionRepository.createSession("Test Session 2", "deepseek-chat", "You are helpful", 2);
    }

    @AfterEach
    void tearDown() throws SQLException {
        var conn = DatabaseConfig.getConnection();
        try (var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM working_memory");
            stmt.execute("DELETE FROM long_term_memory");
            stmt.execute("DELETE FROM messages");
            stmt.execute("DELETE FROM sessions WHERE id IN (" + sessionId1 + ", " + sessionId2 + ")");
        }
    }

    @Test
    void save_to_working_memory() throws Exception {
        var scope = MemoryScope.ofSession(sessionId1);
        long id = memoryService.save(scope, "task", "current_goal", "реализовать auth", MemoryLayer.WORKING);

        assertThat(id).isPositive();

        var result = workingRepo.getByKey(sessionId1, "current_goal");
        assertThat(result).isPresent();
        assertThat(result.get().value()).isEqualTo("реализовать auth");
    }

    @Test
    void save_to_long_term_memory() throws Exception {
        var scope = MemoryScope.ofProfile(1L);
        long id = memoryService.save(scope, "preferences", "language", "русский", MemoryLayer.LONG_TERM);

        assertThat(id).isPositive();

        var result = longTermRepo.getByKey(1L, "language");
        assertThat(result).isPresent();
        assertThat(result.get().value()).isEqualTo("русский");
    }

    @Test
    void get_with_ltm_and_wm_returns_ltm_value() throws Exception {
        var scope = new MemoryScope(sessionId1, 1L);

        memoryService.save(scope, "prefs", "lang", "русский", MemoryLayer.LONG_TERM);
        memoryService.save(scope, "task", "lang", "английский", MemoryLayer.WORKING);

        var result = memoryService.get(scope, "lang");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("русский");
    }

    @Test
    void buildMemoryContext_excludes_wm_duplicates() throws Exception {
        var scope = new MemoryScope(sessionId1, 1L);

        memoryService.save(scope, "prefs", "lang", "русский", MemoryLayer.LONG_TERM);
        memoryService.save(scope, "task", "lang", "английский", MemoryLayer.WORKING);
        memoryService.save(scope, "task", "temp", "value", MemoryLayer.WORKING);

        String context = memoryService.buildMemoryContext(sessionId1);

        assertThat(context).contains("lang: русский");
        assertThat(context).contains("temp: value");
        assertThat(context).doesNotContain("английский");
    }

    @Test
    void get_with_invalid_key_throws_exception() {
        var scope = MemoryScope.ofSession(sessionId1);

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> memoryService.save(scope, "task", "", "value", MemoryLayer.WORKING)
        );

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> memoryService.save(scope, "task", "k".repeat(101), "value", MemoryLayer.WORKING)
        );
    }

    @Test
    void save_long_term_without_profile_throws_exception() {
        var scope = MemoryScope.ofSession(sessionId1);

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> memoryService.save(scope, "prefs", "lang", "русский", MemoryLayer.LONG_TERM)
        );
    }

    @Test
    void delete_from_working_memory() throws Exception {
        var scope = MemoryScope.ofSession(sessionId1);
        memoryService.save(scope, "task", "temp", "value", MemoryLayer.WORKING);

        memoryService.deleteFromMemory(scope, "temp", MemoryLayer.WORKING);

        var result = workingRepo.getByKey(sessionId1, "temp");
        assertThat(result).isEmpty();
    }

    @Test
    void delete_from_long_term_memory() throws Exception {
        var scope = MemoryScope.ofProfile(1L);
        memoryService.save(scope, "prefs", "lang", "русский", MemoryLayer.LONG_TERM);

        memoryService.deleteFromMemory(scope, "lang", MemoryLayer.LONG_TERM);

        var result = longTermRepo.getByKey(1L, "lang");
        assertThat(result).isEmpty();
    }

    @Test
    void get_working_memory_returns_all() throws Exception {
        var scope = MemoryScope.ofSession(sessionId1);
        memoryService.save(scope, "task", "goal1", "value1", MemoryLayer.WORKING);
        memoryService.save(scope, "task", "goal2", "value2", MemoryLayer.WORKING);

        List<WorkingMemoryDto> items = memoryService.getWorkingMemory(sessionId1);

        assertThat(items).hasSize(2);
        assertThat(items).anyMatch(item -> item.key().equals("goal1"));
        assertThat(items).anyMatch(item -> item.key().equals("goal2"));
    }

    @Test
    void get_long_term_memory_returns_all() throws Exception {
        var scope = MemoryScope.ofProfile(1L);
        memoryService.save(scope, "prefs", "lang", "русский", MemoryLayer.LONG_TERM);
        memoryService.save(scope, "prefs", "theme", "dark", MemoryLayer.LONG_TERM);

        List<LongTermMemoryDto> items = memoryService.getLongTermMemory(1L);

        assertThat(items).hasSize(2);
        assertThat(items).anyMatch(item -> item.key().equals("lang"));
        assertThat(items).anyMatch(item -> item.key().equals("theme"));
    }

    @Test
    void save_value_too_large_throws_exception() {
        var scope = MemoryScope.ofSession(sessionId1);

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> memoryService.save(scope, "task", "key", "x".repeat(10_001), MemoryLayer.WORKING)
        );
    }

    @Test
    void save_updates_existing_key() throws Exception {
        var scope = MemoryScope.ofSession(sessionId1);
        memoryService.save(scope, "task", "goal", "value1", MemoryLayer.WORKING);

        memoryService.save(scope, "task", "goal", "value2", MemoryLayer.WORKING);

        var result = workingRepo.getByKey(sessionId1, "goal");
        assertThat(result).isPresent();
        assertThat(result.get().value()).isEqualTo("value2");
    }
}
