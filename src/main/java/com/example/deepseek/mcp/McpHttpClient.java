package com.example.deepseek.mcp;

import com.example.deepseek.mcp.dto.JsonRpcRequest;
import com.example.deepseek.mcp.dto.JsonRpcResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class McpHttpClient {
    private static final Logger log = LoggerFactory.getLogger(McpHttpClient.class);
    private static final int TIMEOUT_SECONDS = 30;
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AtomicInteger requestId = new AtomicInteger(1);
    private final String baseUrl;
    private final Map<String, String> headers;
    private String sessionId;

    public McpHttpClient(String baseUrl, Map<String, String> headers) {
        this.baseUrl = baseUrl;
        this.headers = headers != null ? headers : Map.of();
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();
    }

    public McpHttpResponse sendRequest(JsonRpcRequest request) throws McpTransportException {
        return sendRequest(request, false);
    }

    public McpHttpResponse sendRequest(JsonRpcRequest request, boolean requireSession) throws McpTransportException {
        try {
            String body = objectMapper.writeValueAsString(request);
            log.debug("Sending JSON-RPC request to {}: {}", baseUrl, body);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(body));

            headers.forEach(builder::header);

            if (requireSession && sessionId != null) {
                builder.header(SESSION_HEADER, sessionId);
            }

            HttpRequest httpRequest = builder.build();
            log.debug("Request headers: {}", httpRequest.headers());
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            String responseSessionId = response.headers().firstValue(SESSION_HEADER).orElse(null);
            if (responseSessionId != null) {
                this.sessionId = responseSessionId;
                log.debug("Received session ID: {}", sessionId);
            }

            String contentType = response.headers().firstValue("Content-Type").orElse("");
            log.debug("Response status: {}, Content-Type: {}", response.statusCode(), contentType);

            if (response.statusCode() >= 400) {
                throw new McpTransportException("HTTP error: " + response.statusCode() + " - " + response.body());
            }

            JsonRpcResponse rpcResponse;
            if (contentType.contains("text/event-stream")) {
                rpcResponse = parseSseResponse(response.body(), request.id());
            } else {
                rpcResponse = objectMapper.readValue(response.body(), JsonRpcResponse.class);
            }

            return new McpHttpResponse(rpcResponse, response.headers().map());
        } catch (IOException e) {
            throw new McpTransportException("IO error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpTransportException("Request interrupted", e);
        }
    }

    private JsonRpcResponse parseSseResponse(String body, Object expectedId) throws IOException, McpTransportException {
        List<SseEvent> events = McpSseParser.parse(body);
        log.debug("Parsed {} SSE events", events.size());

        for (SseEvent event : events) {
            if (event.data() != null && !event.data().isBlank()) {
                try {
                    JsonRpcResponse response = objectMapper.readValue(event.data(), JsonRpcResponse.class);
                    if (expectedId == null || expectedId.equals(response.id())) {
                        return response;
                    }
                } catch (IOException e) {
                    log.warn("Failed to parse SSE event data: {}", event.data());
                }
            }
        }

        throw new McpTransportException("No valid JSON-RPC response found in SSE stream");
    }

    public int nextRequestId() {
        return requestId.getAndIncrement();
    }

    public String getSessionId() {
        return sessionId;
    }

    public void close() {
    }
}
