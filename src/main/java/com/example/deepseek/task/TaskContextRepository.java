package com.example.deepseek.task;

import com.example.deepseek.db.DatabaseConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TaskContextRepository {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public long createContext(long taskId, TaskContext context) throws SQLException {
        String sql = """
            INSERT INTO task_context (task_id, task, state, step, total, plan, done, current, previous_state)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try {
            try (Connection conn = DatabaseConfig.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setLong(1, taskId);
                pstmt.setString(2, context.task());
                pstmt.setString(3, context.state().name());
                pstmt.setInt(4, context.step());
                pstmt.setInt(5, context.total());
                pstmt.setString(6, objectMapper.writeValueAsString(context.plan()));
                pstmt.setString(7, objectMapper.writeValueAsString(context.done()));
                pstmt.setString(8, context.current());
                pstmt.setString(9, context.previousState() != null ? context.previousState().name() : null);

                pstmt.executeUpdate();

                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid() as id")) {
                    if (rs.next()) {
                        return rs.getLong("id");
                    }
                }
            }
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to serialize context", e);
        }
        throw new SQLException("Failed to create task context");
    }

    public Optional<TaskContext> getContextByTaskId(long taskId) throws SQLException {
        String sql = "SELECT * FROM task_context WHERE task_id = ?";

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

    public void updateContext(long taskId, TaskContext context) throws SQLException {
        String sql = """
            UPDATE task_context
            SET task = ?, state = ?, step = ?, total = ?, plan = ?, done = ?, current = ?, previous_state = ?
            WHERE task_id = ?
            """;

        try {
            try (Connection conn = DatabaseConfig.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, context.task());
                pstmt.setString(2, context.state().name());
                pstmt.setInt(3, context.step());
                pstmt.setInt(4, context.total());
                pstmt.setString(5, objectMapper.writeValueAsString(context.plan()));
                pstmt.setString(6, objectMapper.writeValueAsString(context.done()));
                pstmt.setString(7, context.current());
                pstmt.setString(8, context.previousState() != null ? context.previousState().name() : null);
                pstmt.setLong(9, taskId);

                pstmt.executeUpdate();
            }
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to serialize context", e);
        }
    }

    public void deleteContext(long taskId) throws SQLException {
        String sql = "DELETE FROM task_context WHERE task_id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, taskId);
            pstmt.executeUpdate();
        }
    }

    public void updateStateOnly(long taskId, TaskState newState) throws SQLException {
        String sql = "UPDATE task_context SET state = ? WHERE task_id = ?";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, newState.name());
            pstmt.setLong(2, taskId);
            pstmt.executeUpdate();
        }
    }

    public void updateStateAndPrevious(long taskId, TaskState newState, TaskState previousState) throws SQLException {
        String sql = "UPDATE task_context SET state = ?, previous_state = ? WHERE task_id = ?";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, newState.name());
            pstmt.setString(2, previousState != null ? previousState.name() : null);
            pstmt.setLong(3, taskId);
            pstmt.executeUpdate();
        }
    }

    private TaskContext mapRow(ResultSet rs) throws SQLException {
        try {
            List<String> plan = parseJsonList(rs.getString("plan"));
            List<String> done = parseJsonList(rs.getString("done"));
            String previousStateStr = rs.getString("previous_state");
            TaskState previousState = previousStateStr != null ? TaskState.valueOf(previousStateStr) : null;

            return new TaskContext(
                rs.getString("task"),
                TaskState.valueOf(rs.getString("state")),
                rs.getInt("step"),
                rs.getInt("total"),
                plan,
                done,
                rs.getString("current"),
                previousState
            );
        } catch (Exception e) {
            throw new SQLException("Failed to parse task context", e);
        }
    }

    private List<String> parseJsonList(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        return objectMapper.readValue(json, new TypeReference<List<String>>() {});
    }
}
