package com.example.deepseek.db;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SummaryRepository {
    private static final Logger log = LoggerFactory.getLogger(SummaryRepository.class);

    public long saveSummary(long sessionId, String content, Integer messageRangeStart, Integer messageRangeEnd) throws SQLException {
        String sql = """
            INSERT INTO summaries (session_id, content, message_range_start, message_range_end)
            VALUES (?, ?, ?, ?)
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            pstmt.setLong(1, sessionId);
            pstmt.setString(2, content);
            
            if (messageRangeStart != null) {
                pstmt.setInt(3, messageRangeStart);
            } else {
                pstmt.setNull(3, Types.INTEGER);
            }
            
            if (messageRangeEnd != null) {
                pstmt.setInt(4, messageRangeEnd);
            } else {
                pstmt.setNull(4, Types.INTEGER);
            }

            pstmt.executeUpdate();

            try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    long id = generatedKeys.getLong(1);
                    log.info("Summary created for session {}: id={}, range=[{}, {}]", 
                            sessionId, id, messageRangeStart, messageRangeEnd);
                    return id;
                }
            }
        }
        throw new SQLException("Failed to create summary, no ID obtained");
    }

    public List<SummaryDto> getSummariesBySession(long sessionId) throws SQLException {
        String sql = """
            SELECT id, session_id, content, message_range_start, message_range_end, created_at
            FROM summaries
            WHERE session_id = ?
            ORDER BY message_range_start ASC
            """;

        List<SummaryDto> summaries = new ArrayList<>();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    summaries.add(new SummaryDto(
                            rs.getLong("id"),
                            rs.getLong("session_id"),
                            rs.getString("content"),
                            rs.getInt("message_range_start") == 0 ? null : rs.getInt("message_range_start"),
                            rs.getInt("message_range_end") == 0 ? null : rs.getInt("message_range_end"),
                            rs.getTimestamp("created_at").toLocalDateTime()
                    ));
                }
            }
        }
        return summaries;
    }

    public static class MessageRange {
        private final int startId;
        private final int endId;

        public MessageRange(int startId, int endId) {
            this.startId = startId;
            this.endId = endId;
        }

        public int startId() { return startId; }
        public int endId() { return endId; }
    }

    public Optional<MessageRange> getLastSummaryRange(long sessionId) throws SQLException {
        String sql = """
            SELECT MAX(message_range_end) as max_end
            FROM summaries
            WHERE session_id = ?
            AND message_range_end IS NOT NULL
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    int maxEnd = rs.getInt("max_end");
                    if (!rs.wasNull()) {
                        String sql2 = """
                            SELECT message_range_start, message_range_end
                            FROM summaries
                            WHERE session_id = ?
                            AND message_range_end = ?
                            """;

                        try (PreparedStatement pstmt2 = conn.prepareStatement(sql2)) {
                            pstmt2.setLong(1, sessionId);
                            pstmt2.setInt(2, maxEnd);

                            try (ResultSet rs2 = pstmt2.executeQuery()) {
                                if (rs2.next()) {
                                    return Optional.of(new MessageRange(
                                            rs2.getInt("message_range_start"),
                                            rs2.getInt("message_range_end")
                                    ));
                                }
                            }
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }
}
