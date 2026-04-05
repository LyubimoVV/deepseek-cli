package com.example.deepseek.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class SessionMcpRepository {
    private static final Logger log = LoggerFactory.getLogger(SessionMcpRepository.class);

    public void setServerEnabled(long sessionId, String serverName, boolean enabled) {
        String sql = """
            INSERT INTO session_mcp_servers (session_id, server_name, enabled)
            VALUES (?, ?, ?)
            ON CONFLICT(session_id, server_name) DO UPDATE SET enabled = ?
            """;
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, sessionId);
            stmt.setString(2, serverName);
            stmt.setInt(3, enabled ? 1 : 0);
            stmt.setInt(4, enabled ? 1 : 0);
            stmt.executeUpdate();
            log.debug("Set MCP server {} enabled={} for session {}", serverName, enabled, sessionId);
        } catch (SQLException e) {
            log.error("Failed to set MCP server enabled: {}", e.getMessage());
            throw new RuntimeException("Failed to set MCP server enabled", e);
        }
    }

    public boolean isServerEnabled(long sessionId, String serverName) {
        String sql = "SELECT enabled FROM session_mcp_servers WHERE session_id = ? AND server_name = ?";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, sessionId);
            stmt.setString(2, serverName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("enabled") == 1;
            }
            return false;
        } catch (SQLException e) {
            log.error("Failed to check MCP server enabled: {}", e.getMessage());
            return false;
        }
    }

    public List<String> getEnabledServers(long sessionId) {
        String sql = "SELECT server_name FROM session_mcp_servers WHERE session_id = ? AND enabled = 1";
        List<String> servers = new ArrayList<>();
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, sessionId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                servers.add(rs.getString("server_name"));
            }
        } catch (SQLException e) {
            log.error("Failed to get enabled MCP servers: {}", e.getMessage());
        }
        return servers;
    }

    public List<SessionMcpServerDto> getAllServersForSession(long sessionId) {
        String sql = "SELECT server_name, enabled FROM session_mcp_servers WHERE session_id = ?";
        List<SessionMcpServerDto> servers = new ArrayList<>();
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, sessionId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                servers.add(new SessionMcpServerDto(
                    rs.getString("server_name"),
                    rs.getInt("enabled") == 1
                ));
            }
        } catch (SQLException e) {
            log.error("Failed to get all MCP servers for session: {}", e.getMessage());
        }
        return servers;
    }

    public void removeServer(long sessionId, String serverName) {
        String sql = "DELETE FROM session_mcp_servers WHERE session_id = ? AND server_name = ?";
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, sessionId);
            stmt.setString(2, serverName);
            stmt.executeUpdate();
            log.debug("Removed MCP server {} from session {}", serverName, sessionId);
        } catch (SQLException e) {
            log.error("Failed to remove MCP server: {}", e.getMessage());
        }
    }

    public void copyServersToSession(long fromSessionId, long toSessionId) {
        String sql = """
            INSERT OR IGNORE INTO session_mcp_servers (session_id, server_name, enabled)
            SELECT ?, server_name, enabled FROM session_mcp_servers WHERE session_id = ?
            """;
        
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, toSessionId);
            stmt.setLong(2, fromSessionId);
            int copied = stmt.executeUpdate();
            log.debug("Copied {} MCP servers from session {} to {}", copied, fromSessionId, toSessionId);
        } catch (SQLException e) {
            log.error("Failed to copy MCP servers: {}", e.getMessage());
        }
    }
}
