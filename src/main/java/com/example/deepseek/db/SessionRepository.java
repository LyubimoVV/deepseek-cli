package com.example.deepseek.db;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.example.deepseek.context.ContextStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SessionRepository {

    private static final Logger log = LoggerFactory.getLogger(SessionRepository.class);
    private static final String ACTIVE_SESSION_KEY = "active_session_id";

    private final ConcurrentHashMap<Long, Long> activeBranchCache = new ConcurrentHashMap<>();

    private BranchRepository branchRepository;

    public void setBranchRepository(BranchRepository branchRepository) {
        this.branchRepository = branchRepository;
        warmUpActiveBranchCache();
    }

    public Long getActiveBranchId(long sessionId) throws SQLException {
        Long cached = activeBranchCache.get(sessionId);
        if (cached != null) {
            return cached;
        }

        if (branchRepository != null) {
            Long dbValue = branchRepository.getActiveBranch(sessionId);
            if (dbValue != null) {
                activeBranchCache.put(sessionId, dbValue);
                return dbValue;
            }
        }

        return 1L;
    }

    public void setActiveBranchId(long sessionId, long branchId) throws SQLException {
        if (branchRepository != null) {
            if (!branchRepository.belongsToSession(branchId, sessionId)) {
                throw new IllegalArgumentException("Branch does not belong to session");
            }

            activeBranchCache.put(sessionId, branchId);
            branchRepository.setActiveBranch(sessionId, branchId);
        }
    }

    private void warmUpActiveBranchCache() {
        if (branchRepository == null) return;

        try {
            var sessions = getAllSessions();
            for (var session : sessions) {
                Long activeBranch = branchRepository.getActiveBranch(session.id());
                if (activeBranch != null) {
                    activeBranchCache.put(session.id(), activeBranch);
                }
            }
            log.info("Active branch cache warmed up with {} sessions", sessions.size());
        } catch (Exception e) {
            log.warn("Failed to warm up active branch cache: {}", e.getMessage());
        }
    }

    public void initializeBranching(long sessionId, int messageCount) throws SQLException {
        if (branchRepository != null) {
            if (!branchRepository.existsMainBranch(sessionId)) {
                branchRepository.createBranch(sessionId, "main", null);
                log.info("Инициализировано ветвление для сессии {}: создана основная ветка, назначено {} сообщений",
                         sessionId, messageCount);
            }
            Long mainBranchId = branchRepository.getMainBranchId(sessionId);
            if (mainBranchId != null) {
                setActiveBranchId(sessionId, mainBranchId);
            }
        }
    }


    public long createSession(String title, String model, String systemMessage, int mode) throws SQLException {
        String sql = """
            INSERT INTO sessions (title, model, system_message, mode, created_at, updated_at, context_strategy, sticky_facts_window_size, sliding_window_size, compression_keep_messages, compression_summary_interval)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            pstmt.setString(7, ContextStrategy.NONE.name());
            pstmt.setInt(8, 10);
            pstmt.setInt(9, 10);
            pstmt.setInt(10, 3);
            pstmt.setInt(11, 10);

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
                   (SELECT COUNT(*) FROM messages WHERE session_id = s.id) as message_count,
                   COALESCE(s.compression_keep_messages, 3) as compression_keep_messages,
                   COALESCE(s.compression_summary_interval, 10) as compression_summary_interval,
                   COALESCE(s.context_strategy, 'NONE') as context_strategy,
                   COALESCE(s.sticky_facts_window_size, 10) as sticky_facts_window_size,
                   COALESCE(s.sliding_window_size, 10) as sliding_window_size
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
                   (SELECT COUNT(*) FROM messages WHERE session_id = s.id) as message_count,
                   COALESCE(s.compression_keep_messages, 3) as compression_keep_messages,
                   COALESCE(s.compression_summary_interval, 10) as compression_summary_interval,
                   COALESCE(s.context_strategy, 'NONE') as context_strategy,
                   COALESCE(s.sticky_facts_window_size, 10) as sticky_facts_window_size,
                   COALESCE(s.sliding_window_size, 10) as sliding_window_size
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
        String strategyStr = rs.getString("context_strategy");
        ContextStrategy strategy;
        try {
            strategy = ContextStrategy.valueOf(strategyStr);
        } catch (IllegalArgumentException e) {
            strategy = ContextStrategy.NONE;
        }
        
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
                rs.getInt("message_count"),
                rs.getInt("compression_keep_messages"),
                rs.getInt("compression_summary_interval"),
                strategy,
                rs.getInt("sticky_facts_window_size"),
                rs.getInt("sliding_window_size")
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

    public record SessionContextSettings(
        int compressionKeepMessages,
        int compressionSummaryInterval,
        int stickyFactsWindowSize,
        int slidingWindowSize
    ) {
        public int summaryBufferSize() {
            return compressionKeepMessages + compressionSummaryInterval;
        }
    }

    public ContextStrategy getContextStrategy(long sessionId) throws SQLException {
        String sql = """
            SELECT COALESCE(context_strategy, 'NONE') as context_strategy
            FROM sessions WHERE id = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String strategyStr = rs.getString("context_strategy");
                    try {
                        return ContextStrategy.valueOf(strategyStr);
                    } catch (IllegalArgumentException e) {
                        return ContextStrategy.NONE;
                    }
                }
            }
        }
        return ContextStrategy.NONE;
    }

    public void updateContextStrategy(long sessionId, ContextStrategy strategy) throws SQLException {
        String sql = "UPDATE sessions SET context_strategy = ? WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, strategy.name());
            pstmt.setLong(2, sessionId);
            pstmt.executeUpdate();

            log.info("Context strategy updated for session {}: {}", sessionId, strategy);
        }
    }

    public int getStickyFactsWindowSize(long sessionId) throws SQLException {
        String sql = """
            SELECT COALESCE(sticky_facts_window_size, 10) as sticky_facts_window_size
            FROM sessions WHERE id = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("sticky_facts_window_size");
                }
            }
        }
        return 10;
    }

    public void updateStickyFactsWindowSize(long sessionId, int windowSize) throws SQLException {
        String sql = "UPDATE sessions SET sticky_facts_window_size = ? WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, windowSize);
            pstmt.setLong(2, sessionId);
            pstmt.executeUpdate();

            log.info("Sticky facts window size updated for session {}: {}", sessionId, windowSize);
        }
    }

    public int getSlidingWindowSize(long sessionId) throws SQLException {
        String sql = """
            SELECT COALESCE(sliding_window_size, 10) as sliding_window_size
            FROM sessions WHERE id = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("sliding_window_size");
                }
            }
        }
        return 10;
    }

    public void updateSlidingWindowSize(long sessionId, int windowSize) throws SQLException {
        String sql = "UPDATE sessions SET sliding_window_size = ? WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, windowSize);
            pstmt.setLong(2, sessionId);
            pstmt.executeUpdate();

            log.info("Sliding window size updated for session {}: {}", sessionId, windowSize);
        }
    }

    public int getCompressionKeepMessages(long sessionId) throws SQLException {
        String sql = """
            SELECT COALESCE(compression_keep_messages, 3) as compression_keep_messages
            FROM sessions WHERE id = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("compression_keep_messages");
                }
            }
        }
        return 3;
    }

    public void updateCompressionKeepMessages(long sessionId, int keepMessages) throws SQLException {
        String sql = "UPDATE sessions SET compression_keep_messages = ? WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, keepMessages);
            pstmt.setLong(2, sessionId);
            pstmt.executeUpdate();

            log.info("Compression keep messages updated for session {}: {}", sessionId, keepMessages);
        }
    }

    public int getCompressionSummaryInterval(long sessionId) throws SQLException {
        String sql = """
            SELECT COALESCE(compression_summary_interval, 10) as compression_summary_interval
            FROM sessions WHERE id = ?
            """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("compression_summary_interval");
                }
            }
        }
        return 10;
    }

    public void updateCompressionSummaryInterval(long sessionId, int summaryInterval) throws SQLException {
        String sql = "UPDATE sessions SET compression_summary_interval = ? WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, summaryInterval);
            pstmt.setLong(2, sessionId);
            pstmt.executeUpdate();

            log.info("Compression summary interval updated for session {}: {}", sessionId, summaryInterval);
        }
    }

    public void updateCompressionSettings(long sessionId, int keepMessages, int summaryInterval) throws SQLException {
        String sql = "UPDATE sessions SET compression_keep_messages = ?, compression_summary_interval = ? WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, keepMessages);
            pstmt.setInt(2, summaryInterval);
            pstmt.setLong(3, sessionId);
            pstmt.executeUpdate();

            log.info("Compression settings updated for session {}: keepMessages={}, summaryInterval={}",
                sessionId, keepMessages, summaryInterval);
        }
    }

    public void clearActiveBranchCache(long sessionId) {
        activeBranchCache.remove(sessionId);
    }
}