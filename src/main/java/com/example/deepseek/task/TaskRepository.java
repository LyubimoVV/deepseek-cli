package com.example.deepseek.task;

import com.example.deepseek.db.DatabaseConfig;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TaskRepository {

    public long createTask(long sessionId, String title, String description, TaskState state) throws SQLException {
        String sql = """
            INSERT INTO tasks (session_id, title, description, state)
            VALUES (?, ?, ?, ?)
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            pstmt.setString(2, title);
            pstmt.setString(3, description);
            pstmt.setString(4, state.name());

            pstmt.executeUpdate();

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid() as id")) {
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
        }
        throw new SQLException("Failed to create task");
    }

    public Optional<TaskDto> getTaskById(long taskId) throws SQLException {
        String sql = "SELECT * FROM tasks WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, taskId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<TaskDto> getTasksBySessionId(long sessionId) throws SQLException {
        String sql = "SELECT * FROM tasks WHERE session_id = ? ORDER BY created_at DESC";

        List<TaskDto> tasks = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    tasks.add(mapRow(rs));
                }
            }
        }
        return tasks;
    }

    public Optional<TaskDto> getLatestTaskBySessionId(long sessionId) throws SQLException {
        String sql = "SELECT * FROM tasks WHERE session_id = ? ORDER BY updated_at DESC LIMIT 1";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public void updateTask(long taskId, String title, String description) throws SQLException {
        String sql = """
            UPDATE tasks
            SET title = ?, description = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, title);
            pstmt.setString(2, description);
            pstmt.setLong(3, taskId);

            pstmt.executeUpdate();
        }
    }

    public void updateTaskState(long taskId, TaskState newState, String expectedAction) throws SQLException {
        String sql = """
            UPDATE tasks
            SET state = ?, expected_action = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, newState.name());
            pstmt.setString(2, expectedAction);
            pstmt.setLong(3, taskId);

            pstmt.executeUpdate();
        }
    }

    public void pauseTask(long taskId, String reason) throws SQLException {
        String sql = """
            UPDATE tasks
            SET paused = 1, pause_reason = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, reason);
            pstmt.setLong(2, taskId);

            pstmt.executeUpdate();
        }
    }

    public void resumeTask(long taskId) throws SQLException {
        String sql = """
            UPDATE tasks
            SET paused = 0, pause_reason = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, taskId);
            pstmt.executeUpdate();
        }
    }

    public void updateTaskContext(long taskId, String context) throws SQLException {
        String sql = """
            UPDATE tasks
            SET context = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, context);
            pstmt.setLong(2, taskId);

            pstmt.executeUpdate();
        }
    }

    public void deleteTask(long taskId) throws SQLException {
        String sql = "DELETE FROM tasks WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, taskId);
            pstmt.executeUpdate();
        }
    }

    public void deleteTasksBySessionId(long sessionId) throws SQLException {
        String sql = "DELETE FROM tasks WHERE session_id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            pstmt.executeUpdate();
        }
    }

    private TaskDto mapRow(ResultSet rs) throws SQLException {
        return new TaskDto(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getString("title"),
            rs.getString("description"),
            TaskState.valueOf(rs.getString("state")),
            rs.getString("expected_action"),
            rs.getInt("paused") == 1,
            rs.getString("pause_reason"),
            rs.getString("context"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }
}
