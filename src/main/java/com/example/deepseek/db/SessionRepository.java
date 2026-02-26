package com.example.deepseek.db;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SessionRepository {

    private static final String ACTIVE_SESSION_KEY = "active_session_id";

    public long createSession(String title, String model, String systemMessage, int mode) throws SQLException {
        String sql = """
            INSERT INTO sessions (title, model, system_message, mode, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            LocalDateTime now = LocalDateTime.now();
            pstmt.setString(1, title != null ? title : "Новая сессия");
            pstmt.setString(2, model);
            pstmt.setString(3, systemMessage);
            pstmt.setInt(4, mode);
            pstmt.setTimestamp(5, Timestamp.valueOf(now));
            pstmt.setTimestamp(6, Timestamp.valueOf(now));

            pstmt.executeUpdate();

            // SQLite не поддерживает getGeneratedKeys, используем last_insert_rowid()
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid() as id")) {
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
            throw new SQLException("Не удалось получить ID созданной сессии");
        }
    }

    public Optional<SessionDto> getSession(long id) throws SQLException {
        String sql = """
            SELECT s.id, s.title, s.model, s.system_message, s.mode,
                   s.total_tokens, s.total_cost, s.request_count,
                   s.created_at, s.updated_at,
                   (SELECT COUNT(*) FROM messages WHERE session_id = s.id) as message_count
            FROM sessions s
            WHERE s.id = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRowToSession(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<SessionDto> getAllSessions() throws SQLException {
        String sql = """
            SELECT s.id, s.title, s.model, s.system_message, s.mode,
                   s.total_tokens, s.total_cost, s.request_count,
                   s.created_at, s.updated_at,
                   (SELECT COUNT(*) FROM messages WHERE session_id = s.id) as message_count
            FROM sessions s
            ORDER BY s.updated_at DESC
            """;

        List<SessionDto> sessions = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                sessions.add(mapRowToSession(rs));
            }
        }
        return sessions;
    }

    public void updateSessionTitle(long id, String title) throws SQLException {
        String sql = "UPDATE sessions SET title = ?, updated_at = ? WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, title);
            pstmt.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            pstmt.setLong(3, id);

            pstmt.executeUpdate();
        }
    }

    public void updateSessionModel(long id, String model) throws SQLException {
        String sql = "UPDATE sessions SET model = ?, updated_at = ? WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, model);
            pstmt.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            pstmt.setLong(3, id);

            pstmt.executeUpdate();
        }
    }

    public void updateSessionTimestamp(long id) throws SQLException {
        String sql = "UPDATE sessions SET updated_at = ? WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            pstmt.setLong(2, id);

            pstmt.executeUpdate();
        }
    }

    public void deleteSession(long id) throws SQLException {
        String sql = "DELETE FROM sessions WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, id);
            pstmt.executeUpdate();
        }
    }

    public Optional<Long> getActiveSessionId() throws SQLException {
        String sql = "SELECT value FROM app_state WHERE key = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, ACTIVE_SESSION_KEY);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getLong("value"));
                }
            }
        }
        return Optional.empty();
    }

    public void setActiveSessionId(long sessionId) throws SQLException {
        String sql = """
            INSERT OR REPLACE INTO app_state (key, value) VALUES (?, ?)
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, ACTIVE_SESSION_KEY);
            pstmt.setString(2, String.valueOf(sessionId));

            pstmt.executeUpdate();
        }
    }

    private SessionDto mapRowToSession(ResultSet rs) throws SQLException {
        return new SessionDto(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("model"),
            rs.getString("system_message"),
            rs.getInt("mode"),
            rs.getInt("total_tokens"),
            rs.getDouble("total_cost"),
            rs.getInt("request_count"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime(),
            rs.getInt("message_count")
        );
    }

    public record SessionStats(int totalTokens, double totalCost, int requestCount) {}

    public SessionStats getSessionStats(long sessionId) throws SQLException {
        String sql = """
            SELECT 
                COALESCE(total_tokens, 0) as total_tokens,
                COALESCE(total_cost, 0.0) as total_cost,
                COALESCE(request_count, 0) as request_count
            FROM sessions
            WHERE id = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return new SessionStats(
                        rs.getInt("total_tokens"),
                        rs.getDouble("total_cost"),
                        rs.getInt("request_count")
                    );
                }
            }
        }
        return new SessionStats(0, 0.0, 0);
    }

    public void updateSessionStats(long sessionId, int tokens, double cost) throws SQLException {
        String sql = """
            UPDATE sessions 
            SET total_tokens = ?,
                total_cost = total_cost + ?,
                request_count = request_count + 1,
                updated_at = ?
            WHERE id = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, tokens);
            pstmt.setDouble(2, cost);
            pstmt.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now()));
            pstmt.setLong(4, sessionId);

            pstmt.executeUpdate();
        }
    }
}
