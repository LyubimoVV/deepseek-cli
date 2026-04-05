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

class TaskRepositoryTest {

    private TaskRepository repo;
    private SessionRepository sessionRepo;
    private long testSessionId;

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseConfig.getConnection();
        repo = new TaskRepository();
        sessionRepo = new SessionRepository();
        testSessionId = sessionRepo.createSession("Test session", "deepseek-chat", null, 2, 1);
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
    void createTask_returnsId() throws SQLException {
        long taskId = repo.createTask(testSessionId, "Test task", "Description", TaskState.PLANNING);

        assertThat(taskId).isPositive();
    }

    @Test
    void getTaskById_returnsTask() throws SQLException {
        long taskId = repo.createTask(testSessionId, "Test task", "Description", TaskState.PLANNING);

        Optional<TaskDto> result = repo.getTaskById(taskId);

        assertThat(result).isPresent();
        assertThat(result.get().title()).isEqualTo("Test task");
        assertThat(result.get().description()).isEqualTo("Description");
        assertThat(result.get().state()).isEqualTo(TaskState.PLANNING);
        assertThat(result.get().paused()).isFalse();
    }

    @Test
    void getTaskById_notFound_returnsEmpty() throws SQLException {
        Optional<TaskDto> result = repo.getTaskById(999999);

        assertThat(result).isEmpty();
    }

    @Test
    void getTasksBySessionId_returnsTasks() throws SQLException {
        repo.createTask(testSessionId, "Task 1", "Desc 1", TaskState.PLANNING);
        repo.createTask(testSessionId, "Task 2", "Desc 2", TaskState.EXECUTION);

        List<TaskDto> tasks = repo.getTasksBySessionId(testSessionId);

        assertThat(tasks).hasSize(2);
        assertThat(tasks.stream().map(TaskDto::title)).containsExactlyInAnyOrder("Task 1", "Task 2");
    }

    @Test
    void updateTask_updatesFields() throws SQLException {
        long taskId = repo.createTask(testSessionId, "Old title", "Old desc", TaskState.PLANNING);

        repo.updateTask(taskId, "New title", "New desc");

        TaskDto updated = repo.getTaskById(taskId).orElseThrow();
        assertThat(updated.title()).isEqualTo("New title");
        assertThat(updated.description()).isEqualTo("New desc");
    }

    @Test
    void updateTaskState_updatesStateAndExpectedAction() throws SQLException {
        long taskId = repo.createTask(testSessionId, "Task", "Desc", TaskState.PLANNING);

        repo.updateTaskState(taskId, TaskState.EXECUTION, "Start execution");

        TaskDto updated = repo.getTaskById(taskId).orElseThrow();
        assertThat(updated.state()).isEqualTo(TaskState.EXECUTION);
        assertThat(updated.expectedAction()).isEqualTo("Start execution");
    }

    @Test
    void pauseTask_setsPausedAndReason() throws SQLException {
        long taskId = repo.createTask(testSessionId, "Task", "Desc", TaskState.EXECUTION);

        repo.pauseTask(taskId, "User requested pause");

        TaskDto paused = repo.getTaskById(taskId).orElseThrow();
        assertThat(paused.paused()).isTrue();
        assertThat(paused.pauseReason()).isEqualTo("User requested pause");
    }

    @Test
    void resumeTask_clearsPausedAndReason() throws SQLException {
        long taskId = repo.createTask(testSessionId, "Task", "Desc", TaskState.EXECUTION);
        repo.pauseTask(taskId, "Some reason");

        repo.resumeTask(taskId);

        TaskDto resumed = repo.getTaskById(taskId).orElseThrow();
        assertThat(resumed.paused()).isFalse();
        assertThat(resumed.pauseReason()).isNull();
    }

    @Test
    void updateTaskContext_setsContext() throws SQLException {
        long taskId = repo.createTask(testSessionId, "Task", "Desc", TaskState.EXECUTION);

        repo.updateTaskContext(taskId, "{\"key\":\"value\"}");

        TaskDto updated = repo.getTaskById(taskId).orElseThrow();
        assertThat(updated.context()).isEqualTo("{\"key\":\"value\"}");
    }

    @Test
    void deleteTask_removesTask() throws SQLException {
        long taskId = repo.createTask(testSessionId, "Task", "Desc", TaskState.PLANNING);

        repo.deleteTask(taskId);

        assertThat(repo.getTaskById(taskId)).isEmpty();
    }

    @Test
    void deleteTasksBySessionId_removesAllTasks() throws SQLException {
        repo.createTask(testSessionId, "Task 1", "Desc 1", TaskState.PLANNING);
        repo.createTask(testSessionId, "Task 2", "Desc 2", TaskState.EXECUTION);

        repo.deleteTasksBySessionId(testSessionId);

        assertThat(repo.getTasksBySessionId(testSessionId)).isEmpty();
    }

    @Test
    void getLatestTaskBySessionId_returnsMostRecent() throws Exception {
        repo.createTask(testSessionId, "Old task", "Desc", TaskState.PLANNING);
        Thread.sleep(1100);
        long latestId = repo.createTask(testSessionId, "Latest task", "Desc", TaskState.EXECUTION);

        Optional<TaskDto> latest = repo.getLatestTaskBySessionId(testSessionId);

        assertThat(latest).isPresent();
        assertThat(latest.get().id()).isEqualTo(latestId);
    }
}
