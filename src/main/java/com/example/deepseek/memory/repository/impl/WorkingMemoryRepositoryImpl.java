package com.example.deepseek.memory.repository.impl;

import com.example.deepseek.db.DatabaseConfig;
import com.example.deepseek.memory.dto.WorkingMemoryDto;
import com.example.deepseek.memory.repository.WorkingMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class WorkingMemoryRepositoryImpl implements WorkingMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(WorkingMemoryRepositoryImpl.class);

    @Override
    public long save(long sessionId, String category, String key, String value, int priority) throws SQLException {
        String sql = """
            INSERT INTO working_memory (session_id, category, key, value, priority, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(session_id, key) DO UPDATE SET
                category = excluded.category,
                value = excluded.value,
                priority = excluded.priority,
                updated_at = excluded.updated_at
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            pstmt.setString(2, category);
            pstmt.setString(3, key);
            pstmt.setString(4, value);
            pstmt.setInt(5, priority);
            pstmt.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now()));

            pstmt.executeUpdate();

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }

            var existing = getByKey(sessionId, key);
            return existing.map(WorkingMemoryDto::id).orElse(-1L);
        }
    }

    @Override
    public Optional<WorkingMemoryDto> getById(long id) throws SQLException {
        String sql = """
            SELECT id, session_id, category, key, value, priority, updated_at
            FROM working_memory
            WHERE id = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<WorkingMemoryDto> getByKey(long sessionId, String key) throws SQLException {
        String sql = """
            SELECT id, session_id, category, key, value, priority, updated_at
            FROM working_memory
            WHERE session_id = ? AND key = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            pstmt.setString(2, key);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<WorkingMemoryDto> getBySession(long sessionId) throws SQLException {
        String sql = """
            SELECT id, session_id, category, key, value, priority, updated_at
            FROM working_memory
            WHERE session_id = ?
            ORDER BY category, key
            """;

        List<WorkingMemoryDto> items = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    items.add(mapRow(rs));
                }
            }
        }
        return items;
    }

    @Override
    public List<WorkingMemoryDto> getBySessionAndCategory(long sessionId, String category) throws SQLException {
        String sql = """
            SELECT id, session_id, category, key, value, priority, updated_at
            FROM working_memory
            WHERE session_id = ? AND category = ?
            ORDER BY key
            """;

        List<WorkingMemoryDto> items = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            pstmt.setString(2, category);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    items.add(mapRow(rs));
                }
            }
        }
        return items;
    }

    @Override
    public List<WorkingMemoryDto> getBySessionAndKeys(long sessionId, java.util.Set<String> keys) throws SQLException {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }

        StringBuilder placeholders = new StringBuilder();
        keys.forEach(k -> placeholders.append("?,"));
        placeholders.deleteCharAt(placeholders.length() - 1);

        String sql = String.format("""
            SELECT id, session_id, category, key, value, priority, updated_at
            FROM working_memory
            WHERE session_id = ? AND key IN (%s)
            ORDER BY key
            """, placeholders);

        List<WorkingMemoryDto> items = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            int index = 2;
            for (String key : keys) {
                pstmt.setString(index++, key);
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    items.add(mapRow(rs));
                }
            }
        }
        return items;
    }

    @Override
    public void update(long id, String category, String key, String value, int priority) throws SQLException {
        String sql = """
            UPDATE working_memory
            SET category = ?, key = ?, value = ?, priority = ?, updated_at = ?
            WHERE id = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, category);
            pstmt.setString(2, key);
            pstmt.setString(3, value);
            pstmt.setInt(4, priority);
            pstmt.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
            pstmt.setLong(6, id);

            pstmt.executeUpdate();
            log.info("Working memory updated: id={}, key={}", id, key);
        }
    }

    @Override
    public void delete(long sessionId, String key) throws SQLException {
        String sql = "DELETE FROM working_memory WHERE session_id = ? AND key = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            pstmt.setString(2, key);
            pstmt.executeUpdate();

            log.info("Working memory deleted: sessionId={}, key={}", sessionId, key);
        }
    }

    @Override
    public void deleteById(long id) throws SQLException {
        String sql = "DELETE FROM working_memory WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, id);
            pstmt.executeUpdate();

            log.info("Working memory deleted by id: id={}", id);
        }
    }

    @Override
    public void deleteAllForSession(long sessionId) throws SQLException {
        String sql = "DELETE FROM working_memory WHERE session_id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            int count = pstmt.executeUpdate();

            log.info("All working memory deleted for session: sessionId={}, count={}", sessionId, count);
        }
    }

    private WorkingMemoryDto mapRow(ResultSet rs) throws SQLException {
        return new WorkingMemoryDto(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getString("category"),
            rs.getString("key"),
            rs.getString("value"),
            rs.getInt("priority"),
            rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }
}
