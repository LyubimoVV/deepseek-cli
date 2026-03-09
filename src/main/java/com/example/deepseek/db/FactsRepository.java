package com.example.deepseek.db;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FactsRepository {
    private static final Logger log = LoggerFactory.getLogger(FactsRepository.class);

    public long saveFact(long sessionId, String category, String key, String value) throws SQLException {
        String sql = """
            INSERT INTO facts (session_id, category, key, value, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(session_id, category, key) DO UPDATE SET
                value = excluded.value,
                updated_at = excluded.updated_at
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            pstmt.setString(2, category);
            pstmt.setString(3, key);
            pstmt.setString(4, value);
            pstmt.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));

            pstmt.executeUpdate();

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        
        var existing = getFactBySessionAndKey(sessionId, category, key);
        return existing.map(f -> f.id()).orElse(-1L);
    }

    private Optional<FactDto> getFactBySessionAndKey(long sessionId, String category, String key) throws SQLException {
        String sql = "SELECT id, session_id, category, key, value, updated_at FROM facts WHERE session_id = ? AND category = ? AND key = ?";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setLong(1, sessionId);
            pstmt.setString(2, category);
            pstmt.setString(3, key);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRowToFact(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<FactDto> getFactsBySession(long sessionId) throws SQLException {
        String sql = """
            SELECT id, session_id, category, key, value, updated_at
            FROM facts
            WHERE session_id = ?
            ORDER BY category, key
            """;

        List<FactDto> facts = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    facts.add(mapRowToFact(rs));
                }
            }
        }
        return facts;
    }

    public List<FactDto> getFactsBySessionAndCategory(long sessionId, String category) throws SQLException {
        String sql = """
            SELECT id, session_id, category, key, value, updated_at
            FROM facts
            WHERE session_id = ? AND category = ?
            ORDER BY key
            """;

        List<FactDto> facts = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            pstmt.setString(2, category);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    facts.add(mapRowToFact(rs));
                }
            }
        }
        return facts;
    }

    public Optional<FactDto> getFactById(long factId) throws SQLException {
        String sql = """
            SELECT id, session_id, category, key, value, updated_at
            FROM facts
            WHERE id = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, factId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRowToFact(rs));
                }
            }
        }
        return Optional.empty();
    }

    public void updateFact(long factId, String category, String key, String value) throws SQLException {
        String sql = """
            UPDATE facts
            SET category = ?, key = ?, value = ?, updated_at = ?
            WHERE id = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, category);
            pstmt.setString(2, key);
            pstmt.setString(3, value);
            pstmt.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now()));
            pstmt.setLong(5, factId);

            pstmt.executeUpdate();
            log.info("Fact updated: id={}, category={}, key={}", factId, category, key);
        }
    }

    public void deleteFact(long factId) throws SQLException {
        String sql = "DELETE FROM facts WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, factId);
            pstmt.executeUpdate();
            log.info("Fact deleted: id={}", factId);
        }
    }

    public void deleteAllFactsForSession(long sessionId) throws SQLException {
        String sql = "DELETE FROM facts WHERE session_id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            pstmt.executeUpdate();
            log.info("All facts deleted for session: sessionId={}", sessionId);
        }
    }

    private FactDto mapRowToFact(ResultSet rs) throws SQLException {
        return new FactDto(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getString("category"),
            rs.getString("key"),
            rs.getString("value"),
            rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }
}
