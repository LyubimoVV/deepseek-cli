package com.example.deepseek.mcp;

import com.example.deepseek.mcp.dto.JsonRpcResponse;

import java.util.List;
import java.util.Map;

public record McpHttpResponse(
    JsonRpcResponse response,
    Map<String, List<String>> headers
) {
    public String getHeader(String name) {
        if (headers == null) return null;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }
}
