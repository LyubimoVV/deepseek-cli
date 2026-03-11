package com.example.deepseek.memory.repository.impl;

import com.example.deepseek.db.DatabaseConfig;
import com.example.deepseek.memory.dto.ProfileDto;
import com.example.deepseek.memory.repository.ProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ProfileRepositoryImpl implements ProfileRepository {

    private static final Logger log = LoggerFactory.getLogger(ProfileRepositoryImpl.class);

    @Override
    public long create(String name, String description, String systemPrompt, String personalization) throws SQLException {
        String sql = """
            INSERT INTO profiles (name, description, system_prompt, personalization, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, name);
            pstmt.setString(2, description);
            pstmt.setString(3, systemPrompt);
            pstmt.setString(4, personalization);
            pstmt.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
            pstmt.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now()));

            pstmt.executeUpdate();

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid() as id")) {
                if (rs.next()) {
                    long id = rs.getLong("id");
                    log.info("Profile created: id={}, name={}", id, name);
                    return id;
                }
            }
        }

        throw new SQLException("Failed to create profile: " + name);
    }

    @Override
    public Optional<ProfileDto> getById(long id) throws SQLException {
        String sql = """
            SELECT id, name, description, system_prompt, personalization, created_at, updated_at
            FROM profiles
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
    public Optional<ProfileDto> getByName(String name) throws SQLException {
        String sql = """
            SELECT id, name, description, system_prompt, personalization, created_at, updated_at
            FROM profiles
            WHERE name = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, name);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<ProfileDto> getAll() throws SQLException {
        String sql = """
            SELECT id, name, description, system_prompt, personalization, created_at, updated_at
            FROM profiles
            ORDER BY name
            """;

        List<ProfileDto> profiles = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    profiles.add(mapRow(rs));
                }
            }
        }
        return profiles;
    }

    @Override
    public void update(long id, String name, String description, String systemPrompt, String personalization) throws SQLException {
        String sql = """
            UPDATE profiles
            SET name = ?, description = ?, system_prompt = ?, personalization = ?, updated_at = ?
            WHERE id = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, name);
            pstmt.setString(2, description);
            pstmt.setString(3, systemPrompt);
            pstmt.setString(4, personalization);
            pstmt.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));
            pstmt.setLong(6, id);

            int count = pstmt.executeUpdate();
            if (count > 0) {
                log.info("Profile updated: id={}, name={}", id, name);
            }
        }
    }

    @Override
    public void delete(long id) throws SQLException {
        String sql = "DELETE FROM profiles WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, id);
            int count = pstmt.executeUpdate();

            if (count > 0) {
                log.info("Profile deleted: id={}", id);
            }
        }
    }

    @Override
    public Optional<ProfileDto> getDefaultProfile() throws SQLException {
        return getByName("Default");
    }

    private ProfileDto mapRow(ResultSet rs) throws SQLException {
        return new ProfileDto(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("system_prompt"),
            rs.getString("personalization"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }
}
