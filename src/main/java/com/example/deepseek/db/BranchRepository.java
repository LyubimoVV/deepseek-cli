package com.example.deepseek.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class BranchRepository {

    private static final Logger log = LoggerFactory.getLogger(BranchRepository.class);

    public BranchDto createBranch(long sessionId, String name, Long parentMessageId) throws SQLException {
        String sql = """
            INSERT INTO conversation_branches (session_id, name, parent_message_id, created_at)
            VALUES (?, ?, ?, ?)
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            pstmt.setString(2, name);
            if (parentMessageId != null) {
                pstmt.setLong(3, parentMessageId);
            } else {
                pstmt.setNull(3, Types.INTEGER);
            }
            pstmt.setTimestamp(4, Timestamp.valueOf(java.time.LocalDateTime.now()));

            pstmt.executeUpdate();

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid() as id")) {
                if (rs.next()) {
                    long branchId = rs.getLong("id");
                    log.info("Branch created: id={}, sessionId={}, name={}, parentId={}", 
                             branchId, sessionId, name, parentMessageId);
                    return getBranchById(branchId).orElseThrow();
                }
            }
            throw new SQLException("Не удалось получить ID созданной ветки");
        }
    }

    public List<BranchDto> getBranchesBySession(long sessionId) throws SQLException {
        String sql = """
            SELECT id, session_id, name, parent_message_id, created_at
            FROM conversation_branches
            WHERE session_id = ?
            ORDER BY id ASC
            """;

        List<BranchDto> branches = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    branches.add(mapRowToBranch(rs));
                }
            }
        }

        log.debug("Loaded {} branches for session {}", branches.size(), sessionId);
        return branches;
    }

    public Optional<BranchDto> getBranchById(long branchId) throws SQLException {
        String sql = """
            SELECT id, session_id, name, parent_message_id, created_at
            FROM conversation_branches
            WHERE id = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, branchId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRowToBranch(rs));
                }
            }
        }
        return Optional.empty();
    }

    public void deleteBranch(long branchId) throws SQLException {
        String sql = "DELETE FROM conversation_branches WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, branchId);
            pstmt.executeUpdate();

            log.info("Branch deleted: id={}", branchId);
        }
    }

    public void deleteBySession(long sessionId) throws SQLException {
        String sql = "DELETE FROM conversation_branches WHERE session_id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, sessionId);
            pstmt.executeUpdate();
            log.info("All branches deleted for session: {}", sessionId);
        }
    }

    public void deleteActiveBranchState(long sessionId) throws SQLException {
        String key = "active_branch_" + sessionId;
        String sql = "DELETE FROM app_state WHERE key = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, key);
            pstmt.executeUpdate();
        }
    }

    public int countBySession(long sessionId) throws SQLException {
        String sql = "SELECT COUNT(*) as count FROM conversation_branches WHERE session_id = ?";

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

    public boolean belongsToSession(long branchId, long sessionId) throws SQLException {
        String sql = "SELECT session_id FROM conversation_branches WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, branchId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("session_id") == sessionId;
                }
            }
        }
        return false;
    }

    public boolean existsMainBranch(long sessionId) throws SQLException {
        String sql = "SELECT id FROM conversation_branches WHERE session_id = ? AND parent_message_id IS NULL LIMIT 1";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Long getMainBranchId(long sessionId) throws SQLException {
        String sql = "SELECT id FROM conversation_branches WHERE session_id = ? AND parent_message_id IS NULL LIMIT 1";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
        }
        return null;
    }

    public void setActiveBranch(long sessionId, long branchId) throws SQLException {
        String key = "active_branch_" + sessionId;
        String sql = """
            INSERT OR REPLACE INTO app_state (key, value) VALUES (?, ?)
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, key);
            pstmt.setString(2, String.valueOf(branchId));
            pstmt.executeUpdate();

            log.info("Active branch set: sessionId={}, branchId={}", sessionId, branchId);
        }
    }

    public Long getActiveBranch(long sessionId) throws SQLException {
        String key = "active_branch_" + sessionId;
        String sql = "SELECT value FROM app_state WHERE key = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, key);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Long.parseLong(rs.getString("value"));
                }
            }
        }
        return null;
    }

    private BranchDto mapRowToBranch(ResultSet rs) throws SQLException {
        Object obj = rs.getObject("parent_message_id");
        Long parentMessageId = (obj == null) ? null : ((Number) obj).longValue();
        return new BranchDto(
            rs.getLong("id"),
            rs.getLong("session_id"),
            rs.getString("name"),
            parentMessageId,
            rs.getTimestamp("created_at").toLocalDateTime()
        );
    }
}
