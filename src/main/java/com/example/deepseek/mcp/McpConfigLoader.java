package com.example.deepseek.mcp;

import com.example.deepseek.mcp.dto.McpServerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class McpConfigLoader {
    private static final Logger log = LoggerFactory.getLogger(McpConfigLoader.class);
    private static final String DEFAULT_CONFIG_PATH = "mcp.json";

    private final ObjectMapper objectMapper;
    private final String configPath;

    public McpConfigLoader() {
        this(DEFAULT_CONFIG_PATH);
    }

    public McpConfigLoader(String configPath) {
        this.objectMapper = new ObjectMapper();
        this.configPath = configPath;
    }

    public Map<String, McpServerConfig> loadServers() {
        Path path = Path.of(configPath);
        if (!Files.exists(path)) {
            log.info("MCP config file not found: {}, creating default", configPath);
            createDefaultConfig(path);
            return Collections.emptyMap();
        }

        try {
            String content = Files.readString(path);
            McpConfigFile config = objectMapper.readValue(content, McpConfigFile.class);
            if (config.servers() == null) {
                return Collections.emptyMap();
            }
            
            Map<String, McpServerConfig> resolvedServers = new LinkedHashMap<>();
            for (var entry : config.servers().entrySet()) {
                McpServerConfig original = entry.getValue();
                McpServerConfig resolved = new McpServerConfig(
                    resolveEnvVariables(original.url()),
                    original.description(),
                    resolveEnvVariables(original.apiKey())
                );
                resolvedServers.put(entry.getKey(), resolved);
            }
            
            log.info("Loaded {} MCP server configurations", resolvedServers.size());
            return resolvedServers;
        } catch (IOException e) {
            log.error("Failed to load MCP config: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    String resolveEnvVariables(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}");
        java.util.regex.Matcher matcher = pattern.matcher(value);
        StringBuilder result = new StringBuilder();
        
        while (matcher.find()) {
            String envVar = matcher.group(1);
            String envValue = System.getenv(envVar);
            String replacement = envValue != null ? envValue : matcher.group(0);
            if (envValue == null) {
                log.warn("Environment variable {} not found, keeping placeholder", envVar);
            }
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }

    private void createDefaultConfig(Path path) {
        try {
            String defaultConfig = """
                {
                  "servers": {
                    "example": {
                      "url": "http://localhost:8080/mcp",
                      "description": "Example MCP server"
                    }
                  }
                }
                """;
            Files.writeString(path, defaultConfig);
            log.info("Created default MCP config at {}", path);
        } catch (IOException e) {
            log.warn("Could not create default MCP config: {}", e.getMessage());
        }
    }

    public void saveServers(Map<String, McpServerConfig> servers) throws IOException {
        McpConfigFile config = new McpConfigFile(servers);
        String content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        Files.writeString(Path.of(configPath), content);
        log.info("Saved {} MCP server configurations", servers.size());
    }

    private record McpConfigFile(Map<String, McpServerConfig> servers) {}
}
