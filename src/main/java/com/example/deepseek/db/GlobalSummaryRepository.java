package com.example.deepseek.db;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GlobalSummaryRepository {
    private static final Logger log = LoggerFactory.getLogger(GlobalSummaryRepository.class);

    public long saveGlobalSummary(
            long sessionId,
            String content,
            int version,
            Long lastMessageId
    ) throws SQLException {
        return saveGlobalSummary(sessionId, content, version, lastMessageId, null, null, null, null);
    }

    public long saveGlobalSummary(
            long sessionId,
            String content,
            int version,
            Long lastMessageId,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            Double cost
    ) throws SQLException {
        String sql = """
            INSERT INTO global_summaries
            (session_id, content, version, last_message_id, updated_at, input_tokens, output_tokens, total_tokens, cost)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);
            pstmt.setString(2, content);
            pstmt.setInt(3, version);
            if (lastMessageId != null) {
                pstmt.setLong(4, lastMessageId);
            } else {
                pstmt.setNull(4, Types.BIGINT);
            }
            pstmt.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));

            if (inputTokens != null) {
                pstmt.setInt(6, inputTokens);
            } else {
                pstmt.setNull(6, Types.INTEGER);
            }

            if (outputTokens != null) {
                pstmt.setInt(7, outputTokens);
            } else {
                pstmt.setNull(7, Types.INTEGER);
            }

            if (totalTokens != null) {
                pstmt.setInt(8, totalTokens);
            } else {
                pstmt.setNull(8, Types.INTEGER);
            }

            if (cost != null) {
                pstmt.setDouble(9, cost);
            } else {
                pstmt.setNull(9, Types.REAL);
            }

            pstmt.executeUpdate();

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    log.info("Global summary saved: id={}, sessionId={}, version={}",
                            id, sessionId, version);
                    return id;
                }
            }
        }
        throw new SQLException("Failed to save global summary, no ID obtained");
    }

    public int getLatestVersion(long sessionId) throws SQLException {
        String sql = "SELECT COALESCE(MAX(version), 0) as max_version FROM global_summaries WHERE session_id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("max_version");
                }
            }
        }
        return 0;
    }

    public Optional<GlobalSummaryDto> getLatestGlobalSummary(long sessionId) throws SQLException {
        String sql = """
            SELECT gs.session_id, gs.content, gs.version, gs.last_message_id, gs.updated_at,
                   gs.input_tokens, gs.output_tokens, gs.total_tokens, gs.cost
            FROM global_summaries gs
            WHERE gs.session_id = ?
            ORDER BY gs.version DESC
            LIMIT 1
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new GlobalSummaryDto(
                            rs.getLong("session_id"),
                            rs.getString("content"),
                            rs.getInt("version"),
                            rs.getLong("last_message_id"),
                            rs.getTimestamp("updated_at").toLocalDateTime(),
                            rs.getInt("input_tokens") == 0 ? null : rs.getInt("input_tokens"),
                            rs.getInt("output_tokens") == 0 ? null : rs.getInt("output_tokens"),
                            rs.getInt("total_tokens") == 0 ? null : rs.getInt("total_tokens"),
                            rs.getDouble("cost") == 0.0 ? null : rs.getDouble("cost")
                    ));
                }
            }
        }
        return Optional.empty();
    }
}
