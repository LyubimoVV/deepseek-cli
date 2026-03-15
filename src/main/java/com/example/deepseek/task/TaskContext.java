package com.example.deepseek.task;

import java.util.List;

public record TaskContext(
    String task,
    TaskState state,
    int step,
    int total,
    List<String> plan,
    List<String> done,
    String current
) {}
