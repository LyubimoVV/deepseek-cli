package com.example.deepseek.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SessionHeartbeatRepository {

    private static final Logger log = LoggerFactory.getLogger(SessionHeartbeatRepository.class);

    public void upsertHeartbeat(long sessionId) throws SQLException {
        String sql = """
            INSERT INTO session_heartbeats (session_id, last_heartbeat, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(session_id) DO UPDATE SET
                last_heartbeat = excluded.last_heartbeat,
                updated_at = excluded.updated_at
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            LocalDateTime now = LocalDateTime.now();
            pstmt.setLong(1, sessionId);
            pstmt.setTimestamp(2, Timestamp.valueOf(now));
            pstmt.setTimestamp(3, Timestamp.valueOf(now));

            pstmt.executeUpdate();
        }
    }

    public Optional<SessionHeartbeatDto> getHeartbeatBySessionId(long sessionId) throws SQLException {
        String sql = "SELECT id, session_id, last_heartbeat, updated_at FROM session_heartbeats WHERE session_id = ?";

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

    public List<SessionHeartbeatDto> getStaleHeartbeats(LocalDateTime threshold) throws SQLException {
        String sql = """
            SELECT id, session_id, last_heartbeat, updated_at
            FROM session_heartbeats
            WHERE last_heartbeat < ?
            """;

        List<SessionHeartbeatDto> result = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setTimestamp(1, Timestamp.valueOf(threshold));

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        }
        return result;
    }

    public List<Long> getSessionsWithoutHeartbeat() throws SQLException {
        String sql = """
            SELECT s.id FROM sessions s
            WHERE NOT EXISTS (
                SELECT 1 FROM session_heartbeats h WHERE h.session_id = s.id
            )
            """;

        List<Long> result = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                result.add(rs.getLong("id"));
            }
        }
        return result;
    }

    public void deleteHeartbeat(long sessionId) throws SQLException {
        String sql = "DELETE FROM session_heartbeats WHERE session_id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            pstmt.executeUpdate();
        }
    }

    private SessionHeartbeatDto mapRow(ResultSet rs) throws SQLException {
        return new SessionHeartbeatDto(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getTimestamp("last_heartbeat").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }
}
