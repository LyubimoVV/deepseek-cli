package com.example.deepseek.task;

import com.example.deepseek.db.DatabaseConfig;
import com.example.deepseek.db.SessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TaskServiceTest {

    private TaskService taskService;
    private TaskRepository taskRepository;
    private TaskContextRepository contextRepository;
    private SessionRepository sessionRepository;
    private long testSessionId;

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseConfig.getConnection();
        taskRepository = new TaskRepository();
        contextRepository = new TaskContextRepository();
        sessionRepository = new SessionRepository();
        taskService = new TaskService();
        
        testSessionId = sessionRepository.createSession("Test session", "deepseek-chat", null, 2, 1);
    }

    @AfterEach
    void tearDown() throws SQLException {
        var conn = DatabaseConfig.getConnection();
        try (var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM task_context WHERE task_id IN (SELECT id FROM tasks WHERE session_id = " + testSessionId + ")");
            stmt.execute("DELETE FROM task_messages WHERE task_id IN (SELECT id FROM tasks WHERE session_id = " + testSessionId + ")");
            stmt.execute("DELETE FROM tasks WHERE session_id = " + testSessionId);
            stmt.execute("DELETE FROM sessions WHERE id = " + testSessionId);
        }
    }

    @Test
    void transitionTask_atomicUpdate_bothTablesUpdated() throws SQLException {
        long taskId = createTaskWithState(TaskState.PLANNING);

        taskService.transitionTask(taskId, TaskState.EXECUTION, null, testSessionId);

        TaskDto task = taskRepository.getTaskById(taskId).orElseThrow();
        TaskContext ctx = contextRepository.getContextByTaskId(taskId).orElseThrow();

        assertThat(task.state()).isEqualTo(TaskState.EXECUTION);
        assertThat(ctx.state()).isEqualTo(TaskState.EXECUTION);
    }

    @Test
    void transitionTask_idempotent_sameStateNoChange() throws SQLException {
        long taskId = createTaskWithState(TaskState.EXECUTION);

        TaskDto result = taskService.transitionTask(taskId, TaskState.EXECUTION, null, testSessionId);

        assertThat(result.state()).isEqualTo(TaskState.EXECUTION);
    }

    @Test
    void transitionTask_validTransition_planningToExecution() throws SQLException {
        long taskId = createTaskWithState(TaskState.PLANNING);

        assertThatCode(() -> taskService.transitionTask(taskId, TaskState.EXECUTION, null, testSessionId))
            .doesNotThrowAnyException();
    }

    @Test
    void transitionTask_validTransition_executionToValidation() throws SQLException {
        long taskId = createTaskWithState(TaskState.EXECUTION);

        assertThatCode(() -> taskService.transitionTask(taskId, TaskState.VALIDATION, null, testSessionId))
            .doesNotThrowAnyException();
    }

    @Test
    void transitionTask_validTransition_validationToDone() throws SQLException {
        long taskId = createTaskWithState(TaskState.VALIDATION);

        assertThatCode(() -> taskService.transitionTask(taskId, TaskState.DONE, null, testSessionId))
            .doesNotThrowAnyException();
    }

    @Test
    void transitionTask_invalidTransition_planningToDone_throwsException() throws SQLException {
        long taskId = createTaskWithState(TaskState.PLANNING);

        assertThatCode(() -> taskService.transitionTask(taskId, TaskState.DONE, null, testSessionId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Invalid transition from PLANNING to DONE");
    }

    private long createTaskWithState(TaskState state) throws SQLException {
        long taskId = taskRepository.createTask(testSessionId, "Test task", "Description", state);
        
        TaskContext ctx = new TaskContext(
            "Test task",
            state,
            1,
            3,
            List.of("Step 1", "Step 2", "Step 3"),
            List.of(),
            "Step 1"
        );
        contextRepository.createContext(taskId, ctx);
        
        return taskId;
    }
}
