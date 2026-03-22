package com.example.deepseek.mcp;

import com.example.deepseek.mcp.dto.McpInitializeResult;
import com.example.deepseek.mcp.dto.McpServerConfig;
import com.example.deepseek.mcp.dto.McpTool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class McpServerConnection {
    public enum Status {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    private final String name;
    private final McpServerConfig config;
    private volatile Status status = Status.DISCONNECTED;
    private McpHttpClient httpClient;
    private McpInitializeResult initializeResult;
    private List<McpTool> tools = new ArrayList<>();
    private String lastError;
    private Instant connectedAt;

    public McpServerConnection(String name, McpServerConfig config) {
        this.name = name;
        this.config = config;
    }

    public String getName() { return name; }
    public McpServerConfig getConfig() { return config; }
    public Status getStatus() { return status; }
    public McpInitializeResult getInitializeResult() { return initializeResult; }
    public List<McpTool> getTools() { return Collections.unmodifiableList(tools); }
    public String getLastError() { return lastError; }
    public Instant getConnectedAt() { return connectedAt; }

    void setStatus(Status status) { this.status = status; }
    void setHttpClient(McpHttpClient client) { this.httpClient = client; }
    void setInitializeResult(McpInitializeResult result) { this.initializeResult = result; }
    void setTools(List<McpTool> tools) { this.tools = new ArrayList<>(tools); }
    void setLastError(String error) { this.lastError = error; }
    void setConnectedAt(Instant instant) { this.connectedAt = instant; }

    McpHttpClient getHttpClient() { return httpClient; }

    public void disconnect() {
        if (httpClient != null) {
            httpClient.close();
            httpClient = null;
        }
        status = Status.DISCONNECTED;
        initializeResult = null;
        tools.clear();
        connectedAt = null;
    }
}
