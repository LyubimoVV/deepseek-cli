package com.example.deepseek.mcp;

import com.example.deepseek.mcp.dto.McpServerConfig;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class McpService {
    private static final Logger log = LoggerFactory.getLogger(McpService.class);
    private static final String CLIENT_NAME = "DeepSeek CLI";
    private static final String CLIENT_VERSION = "1.0.0";

    private final McpConfigLoader configLoader;
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();
    private final Map<String, ConnectionInfo> connectionInfos = new ConcurrentHashMap<>();
    private Map<String, McpServerConfig> serverConfigs = new ConcurrentHashMap<>();

    public McpService() {
        this.configLoader = new McpConfigLoader();
        loadConfig();
    }

    public McpService(String configPath) {
        this.configLoader = new McpConfigLoader(configPath);
        loadConfig();
    }

    private void loadConfig() {
        serverConfigs = new ConcurrentHashMap<>(configLoader.loadServers());
        log.info("MCP Service initialized with {} server configs", serverConfigs.size());
    }

    public void reloadConfig() {
        loadConfig();
    }

    public Map<String, McpServerConfig> getServerConfigs() {
        return Collections.unmodifiableMap(serverConfigs);
    }

    public ConnectionInfo getConnectionInfo(String name) {
        return connectionInfos.get(name);
    }

    public synchronized ConnectionInfo connect(String serverName) throws McpException {
        McpServerConfig config = serverConfigs.get(serverName);
        if (config == null) {
            throw new McpException("Server not found: " + serverName);
        }

        ConnectionInfo existing = connectionInfos.get(serverName);
        if (existing != null && existing.status() == ConnectionInfo.Status.CONNECTED) {
            log.info("Server {} already connected", serverName);
            return existing;
        }

        ConnectionInfo connectionInfo = new ConnectionInfo(
            serverName, 
            config, 
            ConnectionInfo.Status.CONNECTING, 
            null, 
            null, 
            null, 
            null
        );
        connectionInfos.put(serverName, connectionInfo);

        try {
            var transport = HttpClientStreamableHttpTransport.builder(config.url())
                .build();
            
            McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .build();

            clients.put(serverName, client);

            var initResult = client.initialize();
            
            connectionInfo = new ConnectionInfo(
                serverName,
                config,
                ConnectionInfo.Status.CONNECTED,
                initResult.serverInfo().name(),
                initResult.serverInfo().version(),
                Instant.now(),
                null
            );
            connectionInfos.put(serverName, connectionInfo);

            log.info("Connected to {}: {} v{}", serverName, 
                initResult.serverInfo().name(), initResult.serverInfo().version());

            return connectionInfo;
        } catch (Exception e) {
            connectionInfo = new ConnectionInfo(
                serverName,
                config,
                ConnectionInfo.Status.ERROR,
                null,
                null,
                null,
                e.getMessage()
            );
            connectionInfos.put(serverName, connectionInfo);
            clients.remove(serverName);
            log.error("Failed to connect to MCP server {}: {}", serverName, e.getMessage());
            throw new McpException("Connection failed: " + e.getMessage(), e);
        }
    }

    public synchronized void disconnect(String serverName) {
        McpSyncClient client = clients.remove(serverName);
        connectionInfos.remove(serverName);
        
        if (client != null) {
            try {
                client.closeGracefully();
                log.info("Disconnected from MCP server: {}", serverName);
            } catch (Exception e) {
                log.warn("Error closing client for {}: {}", serverName, e.getMessage());
            }
        }
    }

    public void disconnectAll() {
        for (String name : new ArrayList<>(clients.keySet())) {
            disconnect(name);
        }
    }

    public List<Tool> getTools(String serverName) throws McpException {
        McpSyncClient client = clients.get(serverName);
        if (client == null) {
            throw new McpException("Server not connected: " + serverName);
        }
        try {
            ListToolsResult result = client.listTools();
            return result.tools() != null ? result.tools() : List.of();
        } catch (Exception e) {
            throw new McpException("Failed to list tools: " + e.getMessage(), e);
        }
    }

    public List<Tool> getAllTools() {
        List<Tool> allTools = new ArrayList<>();
        for (var entry : clients.entrySet()) {
            try {
                ListToolsResult result = entry.getValue().listTools();
                if (result.tools() != null) {
                    allTools.addAll(result.tools());
                }
            } catch (Exception e) {
                log.warn("Failed to get tools from {}: {}", entry.getKey(), e.getMessage());
            }
        }
        return allTools;
    }

    public Map<String, List<Tool>> getToolsByServer() {
        Map<String, List<Tool>> result = new LinkedHashMap<>();
        for (var entry : clients.entrySet()) {
            try {
                ListToolsResult toolsResult = entry.getValue().listTools();
                result.put(entry.getKey(), toolsResult.tools() != null ? toolsResult.tools() : List.of());
            } catch (Exception e) {
                log.warn("Failed to get tools from {}: {}", entry.getKey(), e.getMessage());
                result.put(entry.getKey(), List.of());
            }
        }
        return result;
    }

    public CallToolResult callTool(String serverName, String toolName, Map<String, Object> args) throws McpException {
        McpSyncClient client = clients.get(serverName);
        if (client == null) {
            throw new McpException("Server not connected: " + serverName);
        }
        try {
            return client.callTool(new CallToolRequest(toolName, args));
        } catch (Exception e) {
            throw new McpException("Tool call failed: " + e.getMessage(), e);
        }
    }
}
