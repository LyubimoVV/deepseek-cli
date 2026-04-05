package com.example.deepseek.task;

import java.util.List;

public record TaskContext(
    String task,
    TaskState state,
    int step,
    int total,
    List<String> plan,
    List<String> done,
    String current,
    TaskState previousState
) {
    public TaskContext(
        String task,
        TaskState state,
        int step,
        int total,
        List<String> plan,
        List<String> done,
        String current
    ) {
        this(task, state, step, total, plan, done, current, null);
    }
}
