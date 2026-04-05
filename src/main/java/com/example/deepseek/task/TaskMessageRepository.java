package com.example.deepseek.task;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.example.deepseek.db.DatabaseConfig;

public class TaskMessageRepository {

    public long saveMessage(long taskId, TaskState taskState, String prompt, String response, int tokensUsed) throws SQLException {
        return saveMessage(taskId, taskState, prompt, response, tokensUsed, null);
    }

    public long saveMessage(long taskId, TaskState taskState, String prompt, String response, int tokensUsed, Integer stepIndex) throws SQLException {
        String sql = """
            INSERT INTO task_messages (task_id, task_state, prompt, response, tokens_used, step_index, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, taskId);
            pstmt.setString(2, taskState.name());
            pstmt.setString(3, prompt);
            pstmt.setString(4, response);
            pstmt.setInt(5, tokensUsed);
            if (stepIndex != null) {
                pstmt.setInt(6, stepIndex);
            } else {
                pstmt.setNull(6, Types.INTEGER);
            }
            pstmt.setTimestamp(7, Timestamp.valueOf(LocalDateTime.now()));

            pstmt.executeUpdate();

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid() as id")) {
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
            throw new SQLException("Не удалось получить ID сохраненного сообщения задачи");
        }
    }

    public List<TaskMessageDto> getByTaskId(long taskId) throws SQLException {
        String sql = """
            SELECT id, task_id, task_state, prompt, response, tokens_used, step_index, created_at
            FROM task_messages
            WHERE task_id = ?
            ORDER BY step_index ASC NULLS LAST, created_at ASC
            """;

        List<TaskMessageDto> messages = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, taskId);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    messages.add(mapRowToMessage(rs));
                }
            }
        }
        return messages;
    }

    public Optional<TaskMessageDto> getByTaskIdAndState(long taskId, TaskState taskState) throws SQLException {
        String sql = """
            SELECT id, task_id, task_state, prompt, response, tokens_used, step_index, created_at
            FROM task_messages
            WHERE task_id = ? AND task_state = ?
            ORDER BY step_index ASC NULLS LAST, created_at DESC
            LIMIT 1
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, taskId);
            pstmt.setString(2, taskState.name());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRowToMessage(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<TaskMessageDto> getAllByTaskIdAndState(long taskId, TaskState taskState) throws SQLException {
        String sql = """
            SELECT id, task_id, task_state, prompt, response, tokens_used, step_index, created_at
            FROM task_messages
            WHERE task_id = ? AND task_state = ?
            ORDER BY step_index ASC NULLS LAST, created_at ASC
            """;

        List<TaskMessageDto> messages = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, taskId);
            pstmt.setString(2, taskState.name());

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    messages.add(mapRowToMessage(rs));
                }
            }
        }
        return messages;
    }

    public void deleteByTaskId(long taskId) throws SQLException {
        String sql = "DELETE FROM task_messages WHERE task_id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, taskId);
            pstmt.executeUpdate();
        }
    }
    
    public void deleteByTaskIdAndState(long taskId, TaskState taskState) throws SQLException {
        String sql = "DELETE FROM task_messages WHERE task_id = ? AND task_state = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, taskId);
            pstmt.setString(2, taskState.name());
            pstmt.executeUpdate();
        }
    }

    private TaskMessageDto mapRowToMessage(ResultSet rs) throws SQLException {
        int stepIndexValue = rs.getInt("step_index");
        Integer stepIndex = rs.wasNull() ? null : stepIndexValue;
        return new TaskMessageDto(
            rs.getLong("id"),
            rs.getLong("task_id"),
            TaskState.valueOf(rs.getString("task_state")),
            rs.getString("prompt"),
            rs.getString("response"),
            rs.getInt("tokens_used"),
            stepIndex,
            rs.getTimestamp("created_at").toLocalDateTime()
        );
    }
}
