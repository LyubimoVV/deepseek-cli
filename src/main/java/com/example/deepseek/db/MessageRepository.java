package com.example.deepseek.db;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MessageRepository {

    public long saveMessage(long sessionId, String role, String content, int inputTokens, int outputTokens, int totalTokens, int cachedTokens, int latency, double cost) throws SQLException {
        String sql = """
            INSERT INTO messages (session_id, role, content, input_tokens, output_tokens, total_tokens, cached_tokens, latency, cost, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            pstmt.setString(2, role);
            pstmt.setString(3, content);
            pstmt.setInt(4, inputTokens);
            pstmt.setInt(5, outputTokens);
            pstmt.setInt(6, totalTokens);
            pstmt.setInt(7, cachedTokens);
            pstmt.setInt(8, latency);
            pstmt.setDouble(9, cost);
            pstmt.setTimestamp(10, Timestamp.valueOf(LocalDateTime.now()));

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
            SELECT id, session_id, role, content, input_tokens, output_tokens, total_tokens, cached_tokens, latency, cost, created_at
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

    public static MessageDto mapRowToMessage(ResultSet rs) throws SQLException {
        return new MessageDto(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getString("role"),
            rs.getString("content"),
            rs.getInt("input_tokens"),
            rs.getInt("output_tokens"),
            rs.getInt("total_tokens"),
            rs.getInt("cached_tokens"),
            rs.getInt("latency"),
            rs.getDouble("cost"),
            rs.getTimestamp("created_at").toLocalDateTime()
        );
    }

    public List<MessageDto> getLastNMessages(long sessionId, int count) throws SQLException {
        String sql = """
            SELECT id, session_id, role, content, input_tokens, output_tokens,
                   total_tokens, cached_tokens, latency, cost, created_at
            FROM messages
            WHERE session_id = ? AND role != 'system'
            ORDER BY id DESC
            LIMIT ?
            """;

        List<MessageDto> messages = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            pstmt.setInt(2, count);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                messages.add(mapRowToMessage(rs));
            }
        }

        Collections.reverse(messages);
        return messages;
    }

    public List<MessageDto> getMessagesAfter(long sessionId, long afterMessageId) throws SQLException {
        String sql = """
            SELECT id, session_id, role, content, input_tokens, output_tokens,
                   total_tokens, cached_tokens, latency, cost, created_at
            FROM messages
            WHERE session_id = ? AND id > ?
            ORDER BY id ASC
            """;

        List<MessageDto> messages = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            pstmt.setLong(2, afterMessageId);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                messages.add(mapRowToMessage(rs));
            }
        }

        return messages;
    }

    public List<MessageDto> getMessagesAfterId(long sessionId, long fromMessageId, int limit) throws SQLException {
        String sql = """
            SELECT id, session_id, role, content, input_tokens, output_tokens,
                   total_tokens, cached_tokens, latency, cost, created_at
            FROM messages
            WHERE session_id = ? AND id > ? AND role != 'system'
            ORDER BY id ASC
            LIMIT ?
            """;

        List<MessageDto> messages = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            pstmt.setLong(2, fromMessageId);
            pstmt.setInt(3, limit);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                messages.add(mapRowToMessage(rs));
            }
        }

        return messages;
    }

    public int getMessageCountAfterId(long sessionId, Long fromMessageId) throws SQLException {
        String sql;

        if (fromMessageId == null) {
            sql = """
                SELECT COUNT(*) FROM messages
                WHERE session_id = ? AND role != 'system'
                """;
        } else {
            sql = """
                SELECT COUNT(*) FROM messages
                WHERE session_id = ? AND id > ? AND role != 'system'
                """;
        }

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            if (fromMessageId != null) {
                pstmt.setLong(2, fromMessageId);
            }

            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        }

        return 0;
    }
}
