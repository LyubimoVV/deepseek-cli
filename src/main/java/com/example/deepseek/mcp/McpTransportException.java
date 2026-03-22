package com.example.deepseek.mcp;

public class McpTransportException extends Exception {
    public McpTransportException(String message) {
        super(message);
    }

    public McpTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
