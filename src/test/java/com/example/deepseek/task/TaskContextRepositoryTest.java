package com.example.deepseek.task;

import com.example.deepseek.db.DatabaseConfig;
import com.example.deepseek.db.SessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TaskContextRepositoryTest {

    private TaskContextRepository repo;
    private TaskRepository taskRepo;
    private SessionRepository sessionRepo;
    private long testSessionId;
    private long testTaskId;

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseConfig.getConnection();
        repo = new TaskContextRepository();
        taskRepo = new TaskRepository();
        sessionRepo = new SessionRepository();
        testSessionId = sessionRepo.createSession("Test session", "deepseek-chat", null, 2, 1);
        testTaskId = taskRepo.createTask(testSessionId, "Test task", "Description", TaskState.PLANNING);
    }

    @AfterEach
    void tearDown() throws SQLException {
        var conn = DatabaseConfig.getConnection();
        try (var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM task_context WHERE task_id = " + testTaskId);
            stmt.execute("DELETE FROM task_messages WHERE task_id = " + testTaskId);
            stmt.execute("DELETE FROM tasks WHERE id = " + testTaskId);
            stmt.execute("DELETE FROM sessions WHERE id = " + testSessionId);
        }
    }

    @Test
    void createContext_returnsId() throws SQLException {
        TaskContext ctx = createTestContext(TaskState.PLANNING);

        long id = repo.createContext(testTaskId, ctx);

        assertThat(id).isPositive();
    }

    @Test
    void getContextByTaskId_returnsContext() throws SQLException {
        TaskContext ctx = createTestContext(TaskState.EXECUTION);
        repo.createContext(testTaskId, ctx);

        Optional<TaskContext> result = repo.getContextByTaskId(testTaskId);

        assertThat(result).isPresent();
        assertThat(result.get().task()).isEqualTo("Test task description");
        assertThat(result.get().state()).isEqualTo(TaskState.EXECUTION);
        assertThat(result.get().step()).isEqualTo(2);
        assertThat(result.get().total()).isEqualTo(3);
        assertThat(result.get().plan()).containsExactly("Step 1", "Step 2", "Step 3");
        assertThat(result.get().done()).containsExactly("Step 1");
        assertThat(result.get().current()).isEqualTo("Step 2");
    }

    @Test
    void getContextByTaskId_notFound_returnsEmpty() throws SQLException {
        Optional<TaskContext> result = repo.getContextByTaskId(999999);

        assertThat(result).isEmpty();
    }

    @Test
    void updateContext_updatesAllFields() throws SQLException {
        repo.createContext(testTaskId, createTestContext(TaskState.PLANNING));

        TaskContext updated = new TaskContext(
            "Updated task",
            TaskState.EXECUTION,
            3,
            5,
            List.of("A", "B", "C", "D", "E"),
            List.of("A", "B"),
            "C"
        );
        repo.updateContext(testTaskId, updated);

        TaskContext result = repo.getContextByTaskId(testTaskId).orElseThrow();
        assertThat(result.task()).isEqualTo("Updated task");
        assertThat(result.state()).isEqualTo(TaskState.EXECUTION);
        assertThat(result.step()).isEqualTo(3);
        assertThat(result.total()).isEqualTo(5);
        assertThat(result.plan()).hasSize(5);
        assertThat(result.done()).hasSize(2);
        assertThat(result.current()).isEqualTo("C");
    }

    @Test
    void updateStateOnly_updatesOnlyState() throws SQLException {
        repo.createContext(testTaskId, createTestContext(TaskState.PLANNING));

        repo.updateStateOnly(testTaskId, TaskState.EXECUTION);

        TaskContext result = repo.getContextByTaskId(testTaskId).orElseThrow();
        assertThat(result.state()).isEqualTo(TaskState.EXECUTION);
        assertThat(result.step()).isEqualTo(2);
        assertThat(result.task()).isEqualTo("Test task description");
    }

    @Test
    void deleteContext_removesContext() throws SQLException {
        repo.createContext(testTaskId, createTestContext(TaskState.PLANNING));

        repo.deleteContext(testTaskId);

        assertThat(repo.getContextByTaskId(testTaskId)).isEmpty();
    }

    @Test
    void createContext_withEmptyDoneList() throws SQLException {
        TaskContext ctx = new TaskContext(
            "Task",
            TaskState.PLANNING,
            1,
            3,
            List.of("Step 1", "Step 2", "Step 3"),
            List.of(),
            "Step 1"
        );

        repo.createContext(testTaskId, ctx);

        TaskContext result = repo.getContextByTaskId(testTaskId).orElseThrow();
        assertThat(result.done()).isEmpty();
    }

    @Test
    void createContext_withCyrillicPlan() throws SQLException {
        TaskContext ctx = new TaskContext(
            "Задача на русском",
            TaskState.PLANNING,
            1,
            2,
            List.of("Шаг 1: анализ", "Шаг 2: реализация"),
            List.of(),
            "Шаг 1: анализ"
        );

        repo.createContext(testTaskId, ctx);

        TaskContext result = repo.getContextByTaskId(testTaskId).orElseThrow();
        assertThat(result.task()).isEqualTo("Задача на русском");
        assertThat(result.plan()).containsExactly("Шаг 1: анализ", "Шаг 2: реализация");
        assertThat(result.current()).isEqualTo("Шаг 1: анализ");
    }

    @Test
    void createContext_withPreviousState() throws SQLException {
        TaskContext ctx = new TaskContext(
            "Task",
            TaskState.PAUSED,
            2,
            3,
            List.of("A", "B", "C"),
            List.of("A"),
            "B",
            TaskState.EXECUTION
        );

        repo.createContext(testTaskId, ctx);

        TaskContext result = repo.getContextByTaskId(testTaskId).orElseThrow();
        assertThat(result.state()).isEqualTo(TaskState.PAUSED);
        assertThat(result.previousState()).isEqualTo(TaskState.EXECUTION);
    }

    @Test
    void updateStateAndPrevious_updatesBothFields() throws SQLException {
        repo.createContext(testTaskId, createTestContext(TaskState.EXECUTION));

        repo.updateStateAndPrevious(testTaskId, TaskState.PAUSED, TaskState.EXECUTION);

        TaskContext result = repo.getContextByTaskId(testTaskId).orElseThrow();
        assertThat(result.state()).isEqualTo(TaskState.PAUSED);
        assertThat(result.previousState()).isEqualTo(TaskState.EXECUTION);
    }

    @Test
    void updateContext_preservesPreviousState() throws SQLException {
        TaskContext ctx = new TaskContext(
            "Task",
            TaskState.PAUSED,
            1,
            3,
            List.of("A", "B", "C"),
            List.of(),
            "A",
            TaskState.EXECUTION
        );
        repo.createContext(testTaskId, ctx);

        TaskContext updated = new TaskContext(
            "Updated task",
            TaskState.PAUSED,
            2,
            3,
            List.of("A", "B", "C"),
            List.of("A"),
            "B",
            TaskState.EXECUTION
        );
        repo.updateContext(testTaskId, updated);

        TaskContext result = repo.getContextByTaskId(testTaskId).orElseThrow();
        assertThat(result.previousState()).isEqualTo(TaskState.EXECUTION);
    }

    private TaskContext createTestContext(TaskState state) {
        return new TaskContext(
            "Test task description",
            state,
            2,
            3,
            List.of("Step 1", "Step 2", "Step 3"),
            List.of("Step 1"),
            "Step 2"
        );
    }
}
