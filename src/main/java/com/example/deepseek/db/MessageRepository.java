package com.example.deepseek.db;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class MessageRepository {

    public long saveMessage(long sessionId, String role, String content, int inputTokens, int outputTokens, int latency, double cost) throws SQLException {
        String sql = """
            INSERT INTO messages (session_id, role, content, input_tokens, output_tokens, latency, cost, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            pstmt.setString(2, role);
            pstmt.setString(3, content);
            pstmt.setInt(4, inputTokens);
            pstmt.setInt(5, outputTokens);
            pstmt.setInt(6, latency);
            pstmt.setDouble(7, cost);
            pstmt.setTimestamp(8, Timestamp.valueOf(LocalDateTime.now()));

            pstmt.executeUpdate();

            // SQLite не поддерживает getGeneratedKeys, используем last_insert_rowid()
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid() as id")) {
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
            throw new SQLException("Не удалось получить ID сохраненного сообщения");
        }
    }

    public List<MessageDto> getMessagesBySession(long sessionId) throws SQLException {
        String sql = """
            SELECT id, session_id, role, content, input_tokens, output_tokens, latency, cost, created_at
            FROM messages
            WHERE session_id = ?
            ORDER BY created_at ASC
            """;

        List<MessageDto> messages = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    messages.add(mapRowToMessage(rs));
                }
            }
        }
        return messages;
    }

    public void deleteMessagesBySession(long sessionId) throws SQLException {
        String sql = "DELETE FROM messages WHERE session_id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            pstmt.executeUpdate();
        }
    }

    public int getMessageCountBySession(long sessionId) throws SQLException {
        String sql = "SELECT COUNT(*) as count FROM messages WHERE session_id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("count");
                }
            }
        }
        return 0;
    }

    public String getFirstUserMessage(long sessionId) throws SQLException {
        String sql = """
            SELECT content FROM messages
            WHERE session_id = ? AND role = 'user'
            ORDER BY created_at ASC
            LIMIT 1
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("content");
                }
            }
        }
        return null;
    }

    private MessageDto mapRowToMessage(ResultSet rs) throws SQLException {
        return new MessageDto(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getString("role"),
            rs.getString("content"),
            rs.getInt("input_tokens"),
            rs.getInt("output_tokens"),
            rs.getInt("latency"),
            rs.getDouble("cost"),
            rs.getTimestamp("created_at").toLocalDateTime()
        );
    }
}
