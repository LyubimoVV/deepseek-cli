package com.example.deepseek.task;

import com.example.deepseek.db.DatabaseConfig;
import com.example.deepseek.db.SessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskMessageRepositoryTest {

    private TaskMessageRepository repo;
    private TaskRepository taskRepo;
    private SessionRepository sessionRepo;
    private long testSessionId;
    private long testTaskId;

    @BeforeEach
    void setUp() throws SQLException {
        repo = new TaskMessageRepository();
        taskRepo = new TaskRepository();
        sessionRepo = new SessionRepository();
        DatabaseConfig.getConnection();
        
        testSessionId = sessionRepo.createSession("Test session", "deepseek-chat", null, 2, 1);
        testTaskId = taskRepo.createTask(testSessionId, "Test task", "Description", TaskState.PLANNING);
    }

    @AfterEach
    void tearDown() throws SQLException {
        var conn = DatabaseConfig.getConnection();
        try (var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM task_messages WHERE task_id = " + testTaskId);
            stmt.execute("DELETE FROM task_context WHERE task_id = " + testTaskId);
            stmt.execute("DELETE FROM tasks WHERE id = " + testTaskId);
            stmt.execute("DELETE FROM sessions WHERE id = " + testSessionId);
        }
    }

    @Test
    void saveMessage_withStepIndex() throws SQLException {
        long id = repo.saveMessage(testTaskId, TaskState.EXECUTION, "prompt", "response", 100, 1);

        assertThat(id).isPositive();

        var opt = repo.getByTaskIdAndState(testTaskId, TaskState.EXECUTION);
        assertThat(opt).isPresent();
        assertThat(opt.get().stepIndex()).isEqualTo(1);
    }

    @Test
    void saveMessage_withoutStepIndex() throws SQLException {
        long id = repo.saveMessage(testTaskId, TaskState.PLANNING, "prompt", "response", 50);

        assertThat(id).isPositive();

        var opt = repo.getByTaskIdAndState(testTaskId, TaskState.PLANNING);
        assertThat(opt).isPresent();
        assertThat(opt.get().stepIndex()).isNull();
    }

    @Test
    void getAllByTaskIdAndState_orderedByStepIndex() throws SQLException {
        repo.saveMessage(testTaskId, TaskState.EXECUTION, "p3", "r3", 0, 3);
        repo.saveMessage(testTaskId, TaskState.EXECUTION, "p1", "r1", 0, 1);
        repo.saveMessage(testTaskId, TaskState.EXECUTION, "p2", "r2", 0, 2);

        List<TaskMessageDto> messages = repo.getAllByTaskIdAndState(testTaskId, TaskState.EXECUTION);

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).stepIndex()).isEqualTo(1);
        assertThat(messages.get(1).stepIndex()).isEqualTo(2);
        assertThat(messages.get(2).stepIndex()).isEqualTo(3);
    }

    @Test
    void getAllByTaskIdAndState_returnsEmptyForNoMatch() throws SQLException {
        List<TaskMessageDto> messages = repo.getAllByTaskIdAndState(testTaskId, TaskState.VALIDATION);

        assertThat(messages).isEmpty();
    }

    @Test
    void getByTaskId_returnsAllMessages() throws SQLException {
        repo.saveMessage(testTaskId, TaskState.PLANNING, "plan", "plan response", 0);
        repo.saveMessage(testTaskId, TaskState.EXECUTION, "exec1", "exec response 1", 0, 1);
        repo.saveMessage(testTaskId, TaskState.EXECUTION, "exec2", "exec response 2", 0, 2);

        List<TaskMessageDto> messages = repo.getByTaskId(testTaskId);

        assertThat(messages).hasSize(3);
    }
}
