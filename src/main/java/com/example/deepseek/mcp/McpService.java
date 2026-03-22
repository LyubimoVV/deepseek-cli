package com.example.deepseek.mcp;

import com.example.deepseek.mcp.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class McpService {
    private static final Logger log = LoggerFactory.getLogger(McpService.class);
    private static final String CLIENT_NAME = "DeepSeek CLI";
    private static final String CLIENT_VERSION = "1.0.0";

    private final McpConfigLoader configLoader;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, McpServerConnection> connections = new ConcurrentHashMap<>();
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

    public Map<String, McpServerConnection> getConnections() {
        return Collections.unmodifiableMap(connections);
    }

    public McpServerConnection getConnection(String name) {
        return connections.get(name);
    }

    public synchronized McpServerConnection connect(String serverName) throws McpException {
        McpServerConfig config = serverConfigs.get(serverName);
        if (config == null) {
            throw new McpException("Server not found: " + serverName);
        }

        McpServerConnection existing = connections.get(serverName);
        if (existing != null && existing.getStatus() == McpServerConnection.Status.CONNECTED) {
            log.info("Server {} already connected", serverName);
            return existing;
        }

        McpServerConnection connection = new McpServerConnection(serverName, config);
        connection.setStatus(McpServerConnection.Status.CONNECTING);
        connections.put(serverName, connection);

        try {
            McpHttpClient client = new McpHttpClient(config.url(), config.headers());
            connection.setHttpClient(client);

            McpInitializeParams initParams = McpInitializeParams.create(CLIENT_NAME, CLIENT_VERSION);
            JsonRpcRequest initRequest = JsonRpcRequest.initialize(initParams, client.nextRequestId());

            log.info("Connecting to MCP server: {} at {}", serverName, config.url());
            McpHttpResponse httpResponse = client.sendRequest(initRequest);
            JsonRpcResponse response = httpResponse.response();

            if (!response.isSuccess()) {
                throw new McpException("Initialize failed: " + 
                    (response.error() != null ? response.error().message() : "Unknown error"));
            }

            McpInitializeResult initResult = objectMapper.convertValue(
                response.result(), McpInitializeResult.class);
            connection.setInitializeResult(initResult);
            log.info("Connected to {}: {} v{}", serverName, 
                initResult.serverInfo().name(), initResult.serverInfo().version());

            try {
                JsonRpcRequest initNotification = JsonRpcRequest.initialized();
                client.sendRequest(initNotification, true);
            } catch (Exception e) {
                log.debug("Ignoring notification response error: {}", e.getMessage());
            }

            fetchTools(connection);

            connection.setStatus(McpServerConnection.Status.CONNECTED);
            connection.setConnectedAt(java.time.Instant.now());
            connection.setLastError(null);

            return connection;
        } catch (McpTransportException e) {
            connection.setStatus(McpServerConnection.Status.ERROR);
            connection.setLastError(e.getMessage());
            throw new McpException("Transport error: " + e.getMessage(), e);
        } catch (Exception e) {
            connection.setStatus(McpServerConnection.Status.ERROR);
            connection.setLastError(e.getMessage());
            throw new McpException("Connection failed: " + e.getMessage(), e);
        }
    }

    private void fetchTools(McpServerConnection connection) throws McpTransportException {
        McpHttpClient client = connection.getHttpClient();
        JsonRpcRequest toolsRequest = JsonRpcRequest.toolsList(client.nextRequestId());
        McpHttpResponse httpResponse = client.sendRequest(toolsRequest, true);
        JsonRpcResponse response = httpResponse.response();

        if (response.isSuccess() && response.result() != null) {
            McpToolsListResult toolsResult = objectMapper.convertValue(
                response.result(), McpToolsListResult.class);
            connection.setTools(toolsResult.tools() != null ? toolsResult.tools() : List.of());
            log.info("Fetched {} tools from {}", connection.getTools().size(), connection.getName());
        } else {
            connection.setTools(List.of());
            log.warn("Failed to fetch tools from {}: {}", connection.getName(),
                response.error() != null ? response.error().message() : "Unknown error");
        }
    }

    public synchronized void disconnect(String serverName) {
        McpServerConnection connection = connections.remove(serverName);
        if (connection != null) {
            connection.disconnect();
            log.info("Disconnected from MCP server: {}", serverName);
        }
    }

    public void disconnectAll() {
        for (String name : new ArrayList<>(connections.keySet())) {
            disconnect(name);
        }
    }

    public List<McpTool> getAllTools() {
        List<McpTool> allTools = new ArrayList<>();
        for (McpServerConnection conn : connections.values()) {
            if (conn.getStatus() == McpServerConnection.Status.CONNECTED) {
                allTools.addAll(conn.getTools());
            }
        }
        return allTools;
    }

    public Map<String, List<McpTool>> getToolsByServer() {
        Map<String, List<McpTool>> result = new LinkedHashMap<>();
        for (McpServerConnection conn : connections.values()) {
            if (conn.getStatus() == McpServerConnection.Status.CONNECTED) {
                result.put(conn.getName(), conn.getTools());
            }
        }
        return result;
    }
}
