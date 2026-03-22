package com.example.deepseek.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record JsonRpcRequest(
    @JsonProperty("jsonrpc") String jsonrpc,
    String method,
    Object params,
    Object id
) {
    private static final String VERSION = "2.0";

    public static JsonRpcRequest notification(String method, Object params) {
        return new JsonRpcRequest(VERSION, method, params, null);
    }

    public static JsonRpcRequest request(String method, Object params, Object id) {
        return new JsonRpcRequest(VERSION, method, params, id);
    }

    public static JsonRpcRequest initialize(Object params, Object id) {
        return request("initialize", params, id);
    }

    public static JsonRpcRequest toolsList(Object id) {
        return request("tools/list", Map.of(), id);
    }

    public static JsonRpcRequest initialized() {
        return notification("notifications/initialized", Map.of());
    }
}
