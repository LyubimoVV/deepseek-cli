package com.example.deepseek.mcp;

import com.example.deepseek.db.SessionMcpRepository;
import com.example.deepseek.dto.FunctionDto;
import com.example.deepseek.dto.ToolCallDto;
import com.example.deepseek.dto.ToolDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class McpToolIntegrationService {
    private static final Logger log = LoggerFactory.getLogger(McpToolIntegrationService.class);
    private static final int MAX_TOOL_ITERATIONS = 10;
    
    private final McpService mcpService;
    private final SessionMcpRepository sessionMcpRepository;
    private final ObjectMapper objectMapper;
    
    public McpToolIntegrationService(McpService mcpService, SessionMcpRepository sessionMcpRepository) {
        this.mcpService = mcpService;
        this.sessionMcpRepository = sessionMcpRepository;
        this.objectMapper = new ObjectMapper();
    }
    
    public List<ToolDto> getToolsForSession(long sessionId) {
        List<String> enabledServers = sessionMcpRepository.getEnabledServers(sessionId);
        if (enabledServers.isEmpty()) {
            return List.of();
        }
        
        List<ToolDto> tools = new ArrayList<>();
        for (String serverName : enabledServers) {
            try {
                if (!isServerConnected(serverName)) {
                    log.info("Auto-connecting to MCP server: {}", serverName);
                    mcpService.connect(serverName);
                }
                
                List<McpSchema.Tool> mcpTools = mcpService.getTools(serverName);
                for (McpSchema.Tool mcpTool : mcpTools) {
                    ToolDto tool = convertMcpToolToLlmTool(serverName, mcpTool);
                    tools.add(tool);
                }
            } catch (McpException e) {
                log.warn("Failed to get tools from server {}: {}", serverName, e.getMessage());
            }
        }
        
        log.info("Loaded {} MCP tools for session {} from {} servers", tools.size(), sessionId, enabledServers.size());
        return tools;
    }
    
    private boolean isServerConnected(String serverName) {
        ConnectionInfo info = mcpService.getConnectionInfo(serverName);
        return info != null && info.status() == ConnectionInfo.Status.CONNECTED;
    }
    
    private ToolDto convertMcpToolToLlmTool(String serverName, McpSchema.Tool mcpTool) {
        String fullName = serverName + "_" + mcpTool.name();
        Map<String, Object> parameters = convertInputSchema(mcpTool.inputSchema());
        
        return ToolDto.function(fullName, mcpTool.description(), parameters);
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertInputSchema(Object inputSchema) {
        if (inputSchema == null) {
            return Map.of("type", "object", "properties", Map.of());
        }
        
        try {
            String json = objectMapper.writeValueAsString(inputSchema);
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to convert input schema: {}", e.getMessage());
            return Map.of("type", "object", "properties", Map.of());
        }
    }
    
    public ToolExecutionResult executeToolCall(ToolCallDto toolCall) {
        String fullName = toolCall.function().name();
        int separatorIndex = fullName.indexOf('_');
        if (separatorIndex == -1) {
            return new ToolExecutionResult(null, "Invalid tool name format: " + fullName, false);
        }
        
        String serverName = fullName.substring(0, separatorIndex);
        String toolName = fullName.substring(separatorIndex + 1);
        
        Map<String, Object> args = parseArguments(toolCall.function().argumentsJson());
        
        try {
            log.info("Executing MCP tool: {} on server {} with args: {}", toolName, serverName, args);
            var result = mcpService.callTool(serverName, toolName, args);
            
            String resultContent = extractResultContent(result);
            log.info("MCP tool {} executed successfully, result length: {}", fullName, resultContent.length());
            
            return new ToolExecutionResult(toolCall.id(), resultContent, true);
        } catch (McpException e) {
            log.error("Failed to execute MCP tool {}: {}", fullName, e.getMessage());
            return new ToolExecutionResult(toolCall.id(), "Error: " + e.getMessage(), false);
        }
    }
    
    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        
        try {
            return objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse tool arguments: {}", e.getMessage());
            return Map.of();
        }
    }
    
    private String extractResultContent(io.modelcontextprotocol.spec.McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        for (var content : result.content()) {
            if (content.type().equals("text")) {
                sb.append(content.toString());
            } else {
                sb.append(content.toString());
            }
        }
        return sb.toString();
    }
    
    public int getMaxToolIterations() {
        return MAX_TOOL_ITERATIONS;
    }
    
    public static record ToolExecutionResult(String toolCallId, String result, boolean success) {}
}
