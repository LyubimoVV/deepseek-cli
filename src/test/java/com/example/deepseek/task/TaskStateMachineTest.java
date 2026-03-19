package com.example.deepseek.task;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStateMachineTest {

    @Test
    void canTransition_planningToExecution_returnsTrue() {
        assertThat(TaskStateMachine.canTransition(TaskState.PLANNING, TaskState.EXECUTION)).isTrue();
    }

    @Test
    void canTransition_executionToValidation_returnsTrue() {
        assertThat(TaskStateMachine.canTransition(TaskState.EXECUTION, TaskState.VALIDATION)).isTrue();
    }

    @Test
    void canTransition_executionToPlanning_returnsTrue() {
        assertThat(TaskStateMachine.canTransition(TaskState.EXECUTION, TaskState.PLANNING)).isTrue();
    }

    @Test
    void canTransition_validationToDone_returnsTrue() {
        assertThat(TaskStateMachine.canTransition(TaskState.VALIDATION, TaskState.DONE)).isTrue();
    }

    @Test
    void canTransition_validationToExecution_returnsTrue() {
        assertThat(TaskStateMachine.canTransition(TaskState.VALIDATION, TaskState.EXECUTION)).isTrue();
    }

    @Test
    void canTransition_planningToDone_returnsFalse() {
        assertThat(TaskStateMachine.canTransition(TaskState.PLANNING, TaskState.DONE)).isFalse();
    }

    @Test
    void canTransition_planningToValidation_returnsFalse() {
        assertThat(TaskStateMachine.canTransition(TaskState.PLANNING, TaskState.VALIDATION)).isFalse();
    }

    @Test
    void canTransition_doneToAny_returnsFalse() {
        assertThat(TaskStateMachine.canTransition(TaskState.DONE, TaskState.PLANNING)).isFalse();
        assertThat(TaskStateMachine.canTransition(TaskState.DONE, TaskState.EXECUTION)).isFalse();
        assertThat(TaskStateMachine.canTransition(TaskState.DONE, TaskState.VALIDATION)).isFalse();
        assertThat(TaskStateMachine.canTransition(TaskState.DONE, TaskState.PAUSED)).isFalse();
    }

    @Test
    void canTransition_planningToPaused_returnsTrue() {
        assertThat(TaskStateMachine.canTransition(TaskState.PLANNING, TaskState.PAUSED)).isTrue();
    }

    @Test
    void canTransition_executionToPaused_returnsTrue() {
        assertThat(TaskStateMachine.canTransition(TaskState.EXECUTION, TaskState.PAUSED)).isTrue();
    }

    @Test
    void canTransition_validationToPaused_returnsTrue() {
        assertThat(TaskStateMachine.canTransition(TaskState.VALIDATION, TaskState.PAUSED)).isTrue();
    }

    @Test
    void canTransition_pausedToPlanning_returnsTrue() {
        assertThat(TaskStateMachine.canTransition(TaskState.PAUSED, TaskState.PLANNING)).isTrue();
    }

    @Test
    void canTransition_pausedToExecution_returnsTrue() {
        assertThat(TaskStateMachine.canTransition(TaskState.PAUSED, TaskState.EXECUTION)).isTrue();
    }

    @Test
    void canTransition_pausedToValidation_returnsTrue() {
        assertThat(TaskStateMachine.canTransition(TaskState.PAUSED, TaskState.VALIDATION)).isTrue();
    }

    @Test
    void canTransition_pausedToDone_returnsFalse() {
        assertThat(TaskStateMachine.canTransition(TaskState.PAUSED, TaskState.DONE)).isFalse();
    }

    @Test
    void canTransition_pausedToPaused_returnsFalse() {
        assertThat(TaskStateMachine.canTransition(TaskState.PAUSED, TaskState.PAUSED)).isFalse();
    }

    @Test
    void getValidTransitions_paused_returnsPlanningExecutionValidation() {
        List<TaskState> transitions = TaskStateMachine.getValidTransitions(TaskState.PAUSED);

        assertThat(transitions).containsExactlyInAnyOrder(TaskState.PLANNING, TaskState.EXECUTION, TaskState.VALIDATION);
    }

    @Test
    void canTransition_nullStates_returnsFalse() {
        assertThat(TaskStateMachine.canTransition(null, TaskState.EXECUTION)).isFalse();
        assertThat(TaskStateMachine.canTransition(TaskState.PLANNING, null)).isFalse();
        assertThat(TaskStateMachine.canTransition(null, null)).isFalse();
    }

    @Test
    void getValidTransitions_planning_returnsExecutionAndPaused() {
        List<TaskState> transitions = TaskStateMachine.getValidTransitions(TaskState.PLANNING);

        assertThat(transitions).containsExactlyInAnyOrder(TaskState.EXECUTION, TaskState.PAUSED);
    }

    @Test
    void getValidTransitions_execution_returnsValidationPlanningPaused() {
        List<TaskState> transitions = TaskStateMachine.getValidTransitions(TaskState.EXECUTION);

        assertThat(transitions).containsExactlyInAnyOrder(TaskState.VALIDATION, TaskState.PLANNING, TaskState.PAUSED);
    }

    @Test
    void getValidTransitions_validation_returnsDoneExecutionPaused() {
        List<TaskState> transitions = TaskStateMachine.getValidTransitions(TaskState.VALIDATION);

        assertThat(transitions).containsExactlyInAnyOrder(TaskState.DONE, TaskState.EXECUTION, TaskState.PAUSED);
    }

    @Test
    void getValidTransitions_done_returnsEmpty() {
        List<TaskState> transitions = TaskStateMachine.getValidTransitions(TaskState.DONE);

        assertThat(transitions).isEmpty();
    }

    @Test
    void getValidTransitions_null_returnsEmpty() {
        List<TaskState> transitions = TaskStateMachine.getValidTransitions(null);

        assertThat(transitions).isEmpty();
    }

    @Test
    void validateTransition_validTransition_noException() {
        org.assertj.core.api.Assertions.assertThatCode(() ->
            TaskStateMachine.validateTransition(TaskState.PLANNING, TaskState.EXECUTION)
        ).doesNotThrowAnyException();
    }

    @Test
    void validateTransition_invalidTransition_throwsException() {
        org.assertj.core.api.Assertions.assertThatCode(() ->
            TaskStateMachine.validateTransition(TaskState.PLANNING, TaskState.DONE)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Invalid transition from PLANNING to DONE");
    }
}
