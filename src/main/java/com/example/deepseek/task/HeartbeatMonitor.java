package com.example.deepseek.task;

import com.example.deepseek.db.SessionHeartbeatDto;
import com.example.deepseek.db.SessionHeartbeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class HeartbeatMonitor {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatMonitor.class);

    private static final long HEARTBEAT_TIMEOUT_MINUTES = 2;
    private static final long CHECK_INTERVAL_MINUTES = 1;

    private final SessionHeartbeatRepository heartbeatRepository;
    private final TaskService taskService;
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public HeartbeatMonitor(TaskService taskService) {
        this.heartbeatRepository = new SessionHeartbeatRepository();
        this.taskService = taskService;
    }

    public void start() {
        if (running) {
            log.warn("HeartbeatMonitor already running");
            return;
        }

        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-monitor");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(
            this::checkHeartbeats,
            CHECK_INTERVAL_MINUTES,
            CHECK_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        );

        log.info("HeartbeatMonitor started (timeout: {} min, check interval: {} min)",
            HEARTBEAT_TIMEOUT_MINUTES, CHECK_INTERVAL_MINUTES);
    }

    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("HeartbeatMonitor stopped");
    }

    private void checkHeartbeats() {
        try {
            LocalDateTime threshold = LocalDateTime.now().minusMinutes(HEARTBEAT_TIMEOUT_MINUTES);
            List<SessionHeartbeatDto> staleHeartbeats = heartbeatRepository.getStaleHeartbeats(threshold);

            if (staleHeartbeats.isEmpty()) {
                return;
            }

            log.debug("Found {} sessions with stale heartbeats", staleHeartbeats.size());

            for (SessionHeartbeatDto heartbeat : staleHeartbeats) {
                pauseActiveTaskForSession(heartbeat.sessionId());
            }
        } catch (SQLException e) {
            log.error("Error checking heartbeats: {}", e.getMessage());
        }
    }

    private void pauseActiveTaskForSession(long sessionId) {
        try {
            var activeTask = taskService.getActiveTask(sessionId);
            if (activeTask.isEmpty()) {
                return;
            }

            TaskDto task = activeTask.get();
            if (task.state() == TaskState.DONE || task.state() == TaskState.PAUSED) {
                return;
            }

            log.info("Pausing task {} for session {} due to heartbeat timeout", task.id(), sessionId);
            taskService.pauseTask(task.id(), "Connection lost (heartbeat timeout)");
        } catch (SQLException e) {
            log.error("Error pausing task for session {}: {}", sessionId, e.getMessage());
        }
    }

    public boolean isRunning() {
        return running;
    }
}
