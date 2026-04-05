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

    @Test
    void pauseTask_setsPausedFlagAndReason() throws SQLException {
        long taskId = createTaskWithState(TaskState.EXECUTION);

        TaskDto result = taskService.pauseTask(taskId, "User requested pause");

        assertThat(result.paused()).isTrue();
        assertThat(result.pauseReason()).isEqualTo("User requested pause");
        assertThat(result.state()).isEqualTo(TaskState.PAUSED);
    }

    @Test
    void pauseTask_savesPreviousState() throws SQLException {
        long taskId = createTaskWithState(TaskState.EXECUTION);

        taskService.pauseTask(taskId, "Pause");

        TaskContext ctx = contextRepository.getContextByTaskId(taskId).orElseThrow();
        assertThat(ctx.state()).isEqualTo(TaskState.PAUSED);
        assertThat(ctx.previousState()).isEqualTo(TaskState.EXECUTION);
    }

    @Test
    void resumeTask_clearsPausedFlagAndRestoresPreviousState() throws SQLException {
        long taskId = createTaskWithState(TaskState.EXECUTION);
        taskService.pauseTask(taskId, "Some reason");

        TaskDto result = taskService.resumeTask(taskId);

        assertThat(result.paused()).isFalse();
        assertThat(result.pauseReason()).isNull();
        assertThat(result.state()).isEqualTo(TaskState.EXECUTION);
    }

    @Test
    void resumeTask_restoresContextState() throws SQLException {
        long taskId = createTaskWithState(TaskState.VALIDATION);
        taskService.pauseTask(taskId, "Pause");

        taskService.resumeTask(taskId);

        TaskContext ctx = contextRepository.getContextByTaskId(taskId).orElseThrow();
        assertThat(ctx.state()).isEqualTo(TaskState.VALIDATION);
        assertThat(ctx.previousState()).isNull();
    }

    @Test
    void resumeTask_withoutPreviousState_defaultsToPlanning() throws SQLException {
        long taskId = createTaskWithState(TaskState.PLANNING);
        taskService.pauseTask(taskId, "Pause");
        contextRepository.updateStateAndPrevious(taskId, TaskState.PAUSED, null);

        TaskDto result = taskService.resumeTask(taskId);

        assertThat(result.state()).isEqualTo(TaskState.PLANNING);
    }

    @Test
    void getActiveTask_excludesPausedTasks() throws SQLException {
        long activeTaskId = createTaskWithState(TaskState.EXECUTION);
        long pausedTaskId = createTaskWithState(TaskState.EXECUTION);
        taskService.pauseTask(pausedTaskId, "Paused");

        var result = taskService.getActiveTask(testSessionId);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(activeTaskId);
    }

    @Test
    void getActiveTask_excludesDoneTasks() throws SQLException {
        createTaskWithState(TaskState.DONE);
        long activeTaskId = createTaskWithState(TaskState.EXECUTION);

        var result = taskService.getActiveTask(testSessionId);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(activeTaskId);
    }

    @Test
    void getActiveTask_noActiveTasks_returnsEmpty() throws SQLException {
        createTaskWithState(TaskState.DONE);
        long pausedTaskId = createTaskWithState(TaskState.EXECUTION);
        taskService.pauseTask(pausedTaskId, "Paused");

        var result = taskService.getActiveTask(testSessionId);

        assertThat(result).isEmpty();
    }

    @Test
    void incrementStep_updatesStepAndCurrent() throws SQLException {
        long taskId = createTaskWithState(TaskState.EXECUTION);

        taskService.incrementStep(taskId);

        TaskContext ctx = contextRepository.getContextByTaskId(taskId).orElseThrow();
        assertThat(ctx.step()).isEqualTo(2);
        assertThat(ctx.current()).isEqualTo("Step 2");
    }

    @Test
    void incrementStep_beyondTotal_throwsException() throws SQLException {
        long taskId = createTaskWithState(TaskState.EXECUTION);
        taskService.incrementStep(taskId);
        taskService.incrementStep(taskId);

        assertThatCode(() -> taskService.incrementStep(taskId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot increment beyond total steps");
    }

    @Test
    void addDone_addsStepToDoneList() throws SQLException {
        long taskId = createTaskWithState(TaskState.EXECUTION);

        taskService.addDone(taskId, "Completed step 1");

        TaskContext ctx = contextRepository.getContextByTaskId(taskId).orElseThrow();
        assertThat(ctx.done()).containsExactly("Completed step 1");
    }

    @Test
    void updateCurrent_updatesCurrentStep() throws SQLException {
        long taskId = createTaskWithState(TaskState.EXECUTION);

        taskService.updateCurrent(taskId, "Modified current step");

        TaskContext ctx = contextRepository.getContextByTaskId(taskId).orElseThrow();
        assertThat(ctx.current()).isEqualTo("Modified current step");
    }

    @Test
    void deleteTask_removesTaskAndContext() throws SQLException {
        long taskId = createTaskWithState(TaskState.EXECUTION);

        taskService.deleteTask(taskId);

        assertThat(taskRepository.getTaskById(taskId)).isEmpty();
        assertThat(contextRepository.getContextByTaskId(taskId)).isEmpty();
    }

    @Test
    void getValidTransitions_returnsCorrectTransitions() {
        List<TaskState> fromPlanning = taskService.getValidTransitions(TaskState.PLANNING);
        List<TaskState> fromDone = taskService.getValidTransitions(TaskState.DONE);
        List<TaskState> fromPaused = taskService.getValidTransitions(TaskState.PAUSED);

        assertThat(fromPlanning).containsExactlyInAnyOrder(TaskState.EXECUTION, TaskState.PAUSED);
        assertThat(fromDone).isEmpty();
        assertThat(fromPaused).containsExactlyInAnyOrder(TaskState.PLANNING, TaskState.EXECUTION, TaskState.VALIDATION);
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
