package com.example.deepseek.app.controllers;

import com.example.deepseek.db.SessionMcpRepository;
import com.example.deepseek.db.SessionMcpServerDto;
import com.example.deepseek.mcp.ConnectionInfo;
import com.example.deepseek.mcp.McpException;
import com.example.deepseek.mcp.McpService;
import com.example.deepseek.mcp.McpSseProxyService;
import com.example.deepseek.mcp.dto.McpServerConfig;
import io.modelcontextprotocol.spec.McpSchema;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CountDownLatch;

public class McpController {
    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final AppContext ctx;
    private SessionMcpRepository sessionMcpRepository;

    public McpController(AppContext ctx) {
        this.ctx = ctx;
    }
    
    public void setSessionMcpRepository(SessionMcpRepository repository) {
        this.sessionMcpRepository = repository;
    }

    public void handleGetServers(Context ctx) {
        log.info("Get MCP servers");
        McpService mcpService = this.ctx.getMcpService();
        if (mcpService == null) {
            ctx.status(500).json(Map.of("success", false, "error", "MCP service not initialized"));
            return;
        }
        
        Long sessionId = getSessionIdFromQuery(ctx);

        List<Map<String, Object>> servers = new ArrayList<>();
        for (Map.Entry<String, McpServerConfig> entry : mcpService.getServerConfigs().entrySet()) {
            String name = entry.getKey();
            McpServerConfig config = entry.getValue();
            ConnectionInfo connInfo = mcpService.getConnectionInfo(name);

            int toolsCount = 0;
            if (connInfo != null && connInfo.status() == ConnectionInfo.Status.CONNECTED) {
                try {
                    toolsCount = mcpService.getTools(name).size();
                } catch (McpException e) {
                    log.warn("Failed to get tools count for {}: {}", name, e.getMessage());
                }
            }
            
            boolean enabledForSession = false;
            if (sessionId != null && sessionMcpRepository != null) {
                enabledForSession = sessionMcpRepository.isServerEnabled(sessionId, name);
            }

            Map<String, Object> server = new LinkedHashMap<>();
            server.put("name", name);
            server.put("url", config.url());
            server.put("description", config.description());
            server.put("status", connInfo != null ? connInfo.status().name() : "DISCONNECTED");
            server.put("toolsCount", toolsCount);
            server.put("enabledForSession", enabledForSession);
            if (connInfo != null && connInfo.lastError() != null) {
                server.put("lastError", connInfo.lastError());
            }
            servers.add(server);
        }

        ctx.json(Map.of("success", true, "servers", servers));
    }
    
    private Long getSessionIdFromQuery(Context ctx) {
        String sessionIdStr = ctx.queryParam("sessionId");
        if (sessionIdStr != null && !sessionIdStr.isBlank()) {
            try {
                return Long.parseLong(sessionIdStr);
            } catch (NumberFormatException e) {
                log.warn("Invalid sessionId parameter: {}", sessionIdStr);
            }
        }
        try {
            return this.ctx.getSessionService().getCurrentSessionId();
        } catch (Exception e) {
            return null;
        }
    }
    
    public void handleGetSessionServers(Context ctx) {
        long sessionId = Long.parseLong(ctx.pathParam("sessionId"));
        log.info("Get MCP servers for session: {}", sessionId);
        
        if (sessionMcpRepository == null) {
            ctx.status(500).json(Map.of("success", false, "error", "Session MCP repository not initialized"));
            return;
        }
        
        McpService mcpService = this.ctx.getMcpService();
        
        List<Map<String, Object>> servers = new ArrayList<>();
        for (Map.Entry<String, McpServerConfig> entry : mcpService.getServerConfigs().entrySet()) {
            String name = entry.getKey();
            McpServerConfig config = entry.getValue();
            ConnectionInfo connInfo = mcpService.getConnectionInfo(name);
            boolean enabled = sessionMcpRepository.isServerEnabled(sessionId, name);
            
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("name", name);
            server.put("description", config.description());
            server.put("enabled", enabled);
            server.put("connected", connInfo != null && connInfo.status() == ConnectionInfo.Status.CONNECTED);
            servers.add(server);
        }
        
        ctx.json(Map.of("success", true, "servers", servers));
    }
    
    public void handleSetSessionServer(Context ctx) {
        long sessionId = Long.parseLong(ctx.pathParam("sessionId"));
        String serverName = ctx.pathParam("serverName");
        
        log.info("Set MCP server {} for session {}", serverName, sessionId);
        
        if (sessionMcpRepository == null) {
            ctx.status(500).json(Map.of("success", false, "error", "Session MCP repository not initialized"));
            return;
        }
        
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
            
            sessionMcpRepository.setServerEnabled(sessionId, serverName, enabled);
            
            ctx.json(Map.of("success", true, "serverName", serverName, "enabled", enabled));
        } catch (Exception e) {
            log.error("Failed to set session MCP server: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
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
            ConnectionInfo connection = mcpService.connect(serverName);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", connection.name());
            result.put("status", connection.status().name());

            if (connection.serverName() != null) {
                result.put("serverInfo", Map.of(
                    "name", connection.serverName(),
                    "version", connection.serverVersion() != null ? connection.serverVersion() : "unknown"
                ));
            }

            try {
                result.put("toolsCount", mcpService.getTools(serverName).size());
            } catch (Exception e) {
                result.put("toolsCount", 0);
            }

            ctx.json(Map.of("success", true, "connection", result));
        } catch (McpException e) {
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

        try {
            List<McpSchema.Tool> tools = mcpService.getTools(serverName);
            ctx.json(Map.of("success", true, "server", serverName, "tools", formatTools(tools)));
        } catch (McpException e) {
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleGetAllTools(Context ctx) {
        log.info("Get all MCP tools");

        McpService mcpService = this.ctx.getMcpService();
        if (mcpService == null) {
            ctx.status(500).json(Map.of("success", false, "error", "MCP service not initialized"));
            return;
        }

        Map<String, List<Map<String, Object>>> toolsByServer = new LinkedHashMap<>();
        for (Map.Entry<String, List<McpSchema.Tool>> entry : mcpService.getToolsByServer().entrySet()) {
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

        ConnectionInfo connection = mcpService.getConnectionInfo(serverName);
        if (connection == null) {
            ctx.json(Map.of("success", true, "status", "DISCONNECTED"));
            return;
        }

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", connection.status().name());
        status.put("connectedAt", connection.connectedAt());

        if (connection.serverName() != null) {
            status.put("serverName", connection.serverName());
            status.put("serverVersion", connection.serverVersion());
        }

        if (connection.lastError() != null) {
            status.put("lastError", connection.lastError());
        }

        try {
            status.put("toolsCount", mcpService.getTools(serverName).size());
        } catch (McpException e) {
            status.put("toolsCount", 0);
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

    public void handleStream(SseClient sseClient) {
        String serverName = sseClient.ctx().pathParam("name");
        log.info("SSE stream request for server: {}", serverName);

        McpSseProxyService sseProxyService = this.ctx.getMcpSseProxyService();
        if (sseProxyService == null) {
            sseClient.sendEvent("error", "{\"error\":\"SSE proxy service not initialized\"}");
            sseClient.close();
            return;
        }

        String citiesParam = sseClient.ctx().queryParam("cities");
        String eventTypesParam = sseClient.ctx().queryParam("eventTypes");
        String lastEventId = sseClient.ctx().header("Last-Event-ID");

        String clientId = UUID.randomUUID().toString();
        McpSseProxyService.ClientSubscription subscription = new McpSseProxyService.ClientSubscription(clientId);

        if (citiesParam != null && !citiesParam.isBlank()) {
            for (String city : citiesParam.split(",")) {
                subscription.addCity(city.trim());
            }
        }

        if (eventTypesParam != null && !eventTypesParam.isBlank()) {
            for (String type : eventTypesParam.split(",")) {
                subscription.addEventType(type.trim());
            }
        }

        if (lastEventId != null && !lastEventId.isBlank()) {
            subscription.setLastEventId(lastEventId);
        }

        CountDownLatch closeLatch = new CountDownLatch(1);
        sseClient.onClose(closeLatch::countDown);
        
        sseProxyService.subscribe(serverName, sseClient, subscription);
        
        try {
            closeLatch.await();
            log.info("SSE client {} disconnected from {}", clientId, serverName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("SSE handler interrupted for client {}", clientId);
        }
    }

    public void handleCallTool(Context ctx) {
        String serverName = ctx.pathParam("name");
        String toolName = ctx.pathParam("tool");
        log.info("Call tool {} on MCP server: {}", toolName, serverName);

        McpService mcpService = this.ctx.getMcpService();
        if (mcpService == null) {
            ctx.status(500).json(Map.of("success", false, "error", "MCP service not initialized"));
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = ctx.bodyAsClass(Map.class);
            var result = mcpService.callTool(serverName, toolName, args);
            ctx.json(Map.of("success", true, "result", result.content()));
        } catch (McpException e) {
            log.error("Failed to call tool {} on {}: {}", toolName, serverName, e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private List<Map<String, Object>> formatTools(List<McpSchema.Tool> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (McpSchema.Tool tool : tools) {
            Map<String, Object> toolMap = new LinkedHashMap<>();
            toolMap.put("name", tool.name());
            toolMap.put("description", tool.description());
            if (tool.inputSchema() != null) {
                toolMap.put("inputSchema", tool.inputSchema());
            }
            result.add(toolMap);
        }
        return result;
    }
}
