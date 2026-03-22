package com.example.deepseek.mcp.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record JsonRpcResponse(
    @JsonProperty("jsonrpc") String jsonrpc,
    Object result,
    JsonRpcError error,
    Object id
) {
    @JsonCreator
    public JsonRpcResponse {}

    public boolean isSuccess() {
        return error == null;
    }

    public record JsonRpcError(
        int code,
        String message,
        Object data
    ) {}
}
