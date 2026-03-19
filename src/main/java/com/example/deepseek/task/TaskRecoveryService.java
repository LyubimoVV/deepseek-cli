package com.example.deepseek.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class TaskRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryService.class);

    private final TaskRepository taskRepository;

    public TaskRecoveryService() {
        this.taskRepository = new TaskRepository();
    }

    public List<TaskDto> findPausedTasks() {
        List<TaskDto> pausedTasks = new ArrayList<>();

        try {
            List<Long> sessionIds = taskRepository.getAllSessionIds();
            for (Long sessionId : sessionIds) {
                List<TaskDto> tasks = taskRepository.getTasksBySessionId(sessionId);
                for (TaskDto task : tasks) {
                    if (task.state() == TaskState.PAUSED) {
                        pausedTasks.add(task);
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Error finding paused tasks: {}", e.getMessage());
        }

        return pausedTasks;
    }

    public List<TaskDto> findActiveTasks() {
        List<TaskDto> activeTasks = new ArrayList<>();

        try {
            List<Long> sessionIds = taskRepository.getAllSessionIds();
            for (Long sessionId : sessionIds) {
                List<TaskDto> tasks = taskRepository.getTasksBySessionId(sessionId);
                for (TaskDto task : tasks) {
                    if (task.state() != TaskState.DONE && task.state() != TaskState.PAUSED) {
                        activeTasks.add(task);
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Error finding active tasks: {}", e.getMessage());
        }

        return activeTasks;
    }

    public int pauseAllActiveTasks(String reason) {
        List<TaskDto> activeTasks = findActiveTasks();
        int pausedCount = 0;

        TaskService taskService = new TaskService();

        for (TaskDto task : activeTasks) {
            try {
                taskService.pauseTask(task.id(), reason);
                log.info("Paused task {} (session {}) - {}", task.id(), task.sessionId(), reason);
                pausedCount++;
            } catch (SQLException e) {
                log.error("Failed to pause task {}: {}", task.id(), e.getMessage());
            }
        }

        return pausedCount;
    }
}
