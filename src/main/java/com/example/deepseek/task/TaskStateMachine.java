package com.example.deepseek.task;

import java.util.List;
import java.util.Map;
import java.util.EnumMap;

public class TaskStateMachine {

    private static final Map<TaskState, List<TaskState>> TRANSITIONS;

    static {
        TRANSITIONS = new EnumMap<>(TaskState.class);
        TRANSITIONS.put(TaskState.PLANNING, List.of(TaskState.EXECUTION));
        TRANSITIONS.put(TaskState.EXECUTION, List.of(TaskState.VALIDATION, TaskState.PLANNING));
        TRANSITIONS.put(TaskState.VALIDATION, List.of(TaskState.DONE, TaskState.EXECUTION));
        TRANSITIONS.put(TaskState.DONE, List.of());
    }

    public static boolean canTransition(TaskState from, TaskState to) {
        if (from == null || to == null) {
            return false;
        }
        List<TaskState> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    public static List<TaskState> getValidTransitions(TaskState current) {
        if (current == null) {
            return List.of();
        }
        return TRANSITIONS.getOrDefault(current, List.of());
    }

    public static void validateTransition(TaskState from, TaskState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException(
                String.format("Invalid transition from %s to %s", from, to)
            );
        }
    }
}
