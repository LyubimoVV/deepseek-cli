package com.example.deepseek.memory.repository.impl;

import com.example.deepseek.db.DatabaseConfig;
import com.example.deepseek.memory.dto.LongTermMemoryDto;
import com.example.deepseek.memory.repository.LongTermMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class LongTermMemoryRepositoryImpl implements LongTermMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryRepositoryImpl.class);

    @Override
    public long save(long profileId, String category, String key, String value, int priority) throws SQLException {
        String sql = """
            INSERT INTO long_term_memory (profile_id, category, key, value, priority, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(profile_id, category, key) DO UPDATE SET
                value = excluded.value,
                priority = excluded.priority,
                updated_at = excluded.updated_at
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, profileId);
            pstmt.setString(2, category);
            pstmt.setString(3, key);
            pstmt.setString(4, value);
            pstmt.setInt(5, priority);
            pstmt.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now()));
            pstmt.setTimestamp(7, Timestamp.valueOf(LocalDateTime.now()));

            pstmt.executeUpdate();

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }

            var existing = getByKey(profileId, key);
            return existing.map(LongTermMemoryDto::id).orElse(-1L);
        }
    }

    @Override
    public Optional<LongTermMemoryDto> getById(long id) throws SQLException {
        String sql = """
            SELECT id, profile_id, category, key, value, priority, created_at, updated_at
            FROM long_term_memory
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
    public Optional<LongTermMemoryDto> getByKey(long profileId, String key) throws SQLException {
        String sql = """
            SELECT id, profile_id, category, key, value, priority, created_at, updated_at
            FROM long_term_memory
            WHERE profile_id = ? AND key = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, profileId);
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
    public List<LongTermMemoryDto> getByProfile(long profileId) throws SQLException {
        String sql = """
            SELECT id, profile_id, category, key, value, priority, created_at, updated_at
            FROM long_term_memory
            WHERE profile_id = ?
            ORDER BY category, key
            """;

        List<LongTermMemoryDto> items = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, profileId);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    items.add(mapRow(rs));
                }
            }
        }
        return items;
    }

    @Override
    public List<LongTermMemoryDto> getByProfileAndCategory(long profileId, String category) throws SQLException {
        String sql = """
            SELECT id, profile_id, category, key, value, priority, created_at, updated_at
            FROM long_term_memory
            WHERE profile_id = ? AND category = ?
            ORDER BY key
            """;

        List<LongTermMemoryDto> items = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, profileId);
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
    public List<LongTermMemoryDto> getByProfileAndKeys(long profileId, java.util.Set<String> keys) throws SQLException {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }

        StringBuilder placeholders = new StringBuilder();
        keys.forEach(k -> placeholders.append("?,"));
        placeholders.deleteCharAt(placeholders.length() - 1);

        String sql = String.format("""
            SELECT id, profile_id, category, key, value, priority, created_at, updated_at
            FROM long_term_memory
            WHERE profile_id = ? AND key IN (%s)
            ORDER BY key
            """, placeholders);

        List<LongTermMemoryDto> items = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, profileId);
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
            UPDATE long_term_memory
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
            log.info("Long-term memory updated: id={}, key={}", id, key);
        }
    }

    @Override
    public void delete(long profileId, String key) throws SQLException {
        String sql = "DELETE FROM long_term_memory WHERE profile_id = ? AND key = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, profileId);
            pstmt.setString(2, key);
            pstmt.executeUpdate();

            log.info("Long-term memory deleted: profileId={}, key={}", profileId, key);
        }
    }

    @Override
    public void deleteById(long id) throws SQLException {
        String sql = "DELETE FROM long_term_memory WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, id);
            pstmt.executeUpdate();

            log.info("Long-term memory deleted by id: id={}", id);
        }
    }

    @Override
    public void deleteAllForProfile(long profileId) throws SQLException {
        String sql = "DELETE FROM long_term_memory WHERE profile_id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, profileId);
            int count = pstmt.executeUpdate();

            log.info("All long-term memory deleted for profile: profileId={}, count={}", profileId, count);
        }
    }

    private LongTermMemoryDto mapRow(ResultSet rs) throws SQLException {
        return new LongTermMemoryDto(
            rs.getLong("id"),
            rs.getLong("profile_id"),
            rs.getString("category"),
            rs.getString("key"),
            rs.getString("value"),
            rs.getInt("priority"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }
}
