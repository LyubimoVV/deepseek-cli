package com.example.deepseek.app.controllers;

import com.example.deepseek.mcp.McpServerConnection;
import com.example.deepseek.mcp.McpService;
import com.example.deepseek.mcp.dto.McpServerConfig;
import com.example.deepseek.mcp.dto.McpTool;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class McpController {
    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final AppContext ctx;

    public McpController(AppContext ctx) {
        this.ctx = ctx;
    }

    public void handleGetServers(Context ctx) {
        log.info("Get MCP servers");
        McpService mcpService = this.ctx.getMcpService();
        if (mcpService == null) {
            ctx.status(500).json(Map.of("success", false, "error", "MCP service not initialized"));
            return;
        }

        List<Map<String, Object>> servers = new ArrayList<>();
        for (Map.Entry<String, McpServerConfig> entry : mcpService.getServerConfigs().entrySet()) {
            String name = entry.getKey();
            McpServerConfig config = entry.getValue();
            McpServerConnection conn = mcpService.getConnection(name);

            Map<String, Object> server = new LinkedHashMap<>();
            server.put("name", name);
            server.put("url", config.url());
            server.put("description", config.description());
            server.put("status", conn != null ? conn.getStatus().name() : "DISCONNECTED");
            server.put("toolsCount", conn != null ? conn.getTools().size() : 0);
            if (conn != null && conn.getLastError() != null) {
                server.put("lastError", conn.getLastError());
            }
            servers.add(server);
        }

        ctx.json(Map.of("success", true, "servers", servers));
    }

    public void handleConnect(Context ctx) {
        String serverName = ctx.pathParam("name");
        log.info("Connect to MCP server: {}", serverName);

        McpService mcpService = this.ctx.getMcpService();
        if (mcpService == null) {
            ctx.status(500).json(Map.of("success", false, "error", "MCP service not initialized"));
            return;
        }

        try {
            McpServerConnection connection = mcpService.connect(serverName);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", connection.getName());
            result.put("status", connection.getStatus().name());
            result.put("toolsCount", connection.getTools().size());

            if (connection.getInitializeResult() != null) {
                var serverInfo = connection.getInitializeResult().serverInfo();
                result.put("serverInfo", Map.of(
                    "name", serverInfo.name(),
                    "version", serverInfo.version()
                ));
            }

            ctx.json(Map.of("success", true, "connection", result));
        } catch (Exception e) {
            log.error("Failed to connect to MCP server {}: {}", serverName, e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleDisconnect(Context ctx) {
        String serverName = ctx.pathParam("name");
        log.info("Disconnect from MCP server: {}", serverName);

        McpService mcpService = this.ctx.getMcpService();
        if (mcpService == null) {
            ctx.status(500).json(Map.of("success", false, "error", "MCP service not initialized"));
            return;
        }

        mcpService.disconnect(serverName);
        ctx.json(Map.of("success", true));
    }

    public void handleGetTools(Context ctx) {
        String serverName = ctx.pathParam("name");
        log.info("Get tools from MCP server: {}", serverName);

        McpService mcpService = this.ctx.getMcpService();
        if (mcpService == null) {
            ctx.status(500).json(Map.of("success", false, "error", "MCP service not initialized"));
            return;
        }

        McpServerConnection connection = mcpService.getConnection(serverName);
        if (connection == null) {
            ctx.status(404).json(Map.of("success", false, "error", "Server not connected"));
            return;
        }

        List<Map<String, Object>> tools = formatTools(connection.getTools());
        ctx.json(Map.of("success", true, "server", serverName, "tools", tools));
    }

    public void handleGetAllTools(Context ctx) {
        log.info("Get all MCP tools");

        McpService mcpService = this.ctx.getMcpService();
        if (mcpService == null) {
            ctx.status(500).json(Map.of("success", false, "error", "MCP service not initialized"));
            return;
        }

        Map<String, List<Map<String, Object>>> toolsByServer = new LinkedHashMap<>();
        for (Map.Entry<String, List<McpTool>> entry : mcpService.getToolsByServer().entrySet()) {
            toolsByServer.put(entry.getKey(), formatTools(entry.getValue()));
        }

        ctx.json(Map.of("success", true, "toolsByServer", toolsByServer));
    }

    public void handleGetStatus(Context ctx) {
        String serverName = ctx.pathParam("name");
        log.info("Get MCP server status: {}", serverName);

        McpService mcpService = this.ctx.getMcpService();
        if (mcpService == null) {
            ctx.status(500).json(Map.of("success", false, "error", "MCP service not initialized"));
            return;
        }

        McpServerConnection connection = mcpService.getConnection(serverName);
        if (connection == null) {
            ctx.json(Map.of("success", true, "status", "DISCONNECTED"));
            return;
        }

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", connection.getStatus().name());
        status.put("toolsCount", connection.getTools().size());
        status.put("connectedAt", connection.getConnectedAt());

        if (connection.getInitializeResult() != null) {
            var serverInfo = connection.getInitializeResult().serverInfo();
            status.put("serverName", serverInfo.name());
            status.put("serverVersion", serverInfo.version());
        }

        if (connection.getLastError() != null) {
            status.put("lastError", connection.getLastError());
        }

        ctx.json(Map.of("success", true, "status", status));
    }

    public void handleReloadConfig(Context ctx) {
        log.info("Reload MCP config");

        McpService mcpService = this.ctx.getMcpService();
        if (mcpService == null) {
            ctx.status(500).json(Map.of("success", false, "error", "MCP service not initialized"));
            return;
        }

        mcpService.reloadConfig();
        ctx.json(Map.of("success", true, "serversCount", mcpService.getServerConfigs().size()));
    }

    private List<Map<String, Object>> formatTools(List<McpTool> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (McpTool tool : tools) {
            Map<String, Object> toolMap = new LinkedHashMap<>();
            toolMap.put("name", tool.name());
            toolMap.put("description", tool.description());
            if (tool.inputSchema() != null) {
                toolMap.put("inputSchema", Map.of(
                    "type", tool.inputSchema().type() != null ? tool.inputSchema().type() : "object",
                    "properties", tool.inputSchema().properties() != null ? tool.inputSchema().properties() : Map.of(),
                    "required", tool.inputSchema().required() != null ? tool.inputSchema().required() : List.of()
                ));
            }
            result.add(toolMap);
        }
        return result;
    }
}
