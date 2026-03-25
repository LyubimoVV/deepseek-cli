package com.example.deepseek.mcp;

import com.example.deepseek.mcp.dto.McpServerConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.sse.SseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class McpSseProxyService {
    private static final Logger log = LoggerFactory.getLogger(McpSseProxyService.class);
    private static final int EVENT_BUFFER_SIZE = 100;
    private static final long RECONNECT_DELAY_SECONDS = 5;

    private final McpService mcpService;
    private final ObjectMapper objectMapper;
    private final Map<String, ServerConnection> serverConnections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public McpSseProxyService(McpService mcpService) {
        this.mcpService = mcpService;
        this.objectMapper = new ObjectMapper();
    }

    public void subscribe(String serverName, SseClient client, ClientSubscription subscription) {
        McpServerConfig config = mcpService.getServerConfigs().get(serverName);
        if (config == null) {
            client.sendEvent("error", "{\"error\":\"Server not found: " + serverName + "\"}");
            client.close();
            return;
        }

        String baseUrl = extractBaseUrl(config.url());
        String streamUrl = baseUrl + "/mcp/stream";
        
        log.info("Subscribing to {} with apiKey: {}", serverName, 
            config.apiKey() != null ? "present(" + config.apiKey().length() + " chars)" : "null");

        ServerConnection serverConn = serverConnections.computeIfAbsent(serverName, 
            k -> new ServerConnection(serverName, streamUrl, config.apiKey()));

        client.onClose(() -> {
            serverConn.removeClient(subscription.clientId);
            if (serverConn.isEmpty()) {
                log.info("No more clients for server {}, stopping connection", serverName);
                serverConn.stop();
                serverConnections.remove(serverName);
            }
        });

        serverConn.addClient(client, subscription);

        String connectEventId = serverConn.eventBuffer.generateEventId();
        String connectData = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{\"level\":\"info\",\"data\":{\"type\":\"connected\",\"clientId\":\"" + subscription.clientId + "\",\"server\":\"" + serverName + "\"}}}";
        try {
            client.sendEvent("message", connectData, connectEventId);
        } catch (Exception e) {
            log.debug("Client closed before connect event: {}", e.getMessage());
            return;
        }

        String lastEventId = subscription.getLastEventId();
        if (lastEventId != null && !lastEventId.isEmpty()) {
            for (BufferedEvent event : serverConn.eventBuffer.getAfter(lastEventId)) {
                if (matchesSubscription(event, subscription)) {
                    try {
                        client.sendEvent("message", event.data, event.eventId);
                    } catch (Exception e) {
                        log.debug("Client closed during replay: {}", e.getMessage());
                        return;
                    }
                }
            }
        }

        serverConn.ensureConnected();
    }

    private String extractBaseUrl(String mcpUrl) {
        if (mcpUrl.endsWith("/mcp")) {
            return mcpUrl.substring(0, mcpUrl.length() - 4);
        }
        return mcpUrl;
    }

    private boolean matchesSubscription(BufferedEvent event, ClientSubscription sub) {
        boolean cityMatch = sub.cities.isEmpty() || (event.city != null && sub.cities.contains(event.city));
        boolean typeMatch = sub.eventTypes.isEmpty() || (event.eventType != null && sub.eventTypes.contains(event.eventType));
        return cityMatch && typeMatch;
    }

    public void shutdown() {
        scheduler.shutdown();
        for (ServerConnection conn : serverConnections.values()) {
            conn.stop();
        }
        serverConnections.clear();
    }

    public int getClientCount(String serverName) {
        ServerConnection conn = serverConnections.get(serverName);
        return conn != null ? conn.clientCount() : 0;
    }

    public boolean isConnected(String serverName) {
        ServerConnection conn = serverConnections.get(serverName);
        return conn != null && conn.isConnected();
    }

    public class ServerConnection {
        private final String serverName;
        private final String streamUrl;
        private final String apiKey;
        private final EventBuffer eventBuffer = new EventBuffer();
        private final List<ClientEntry> clients = new CopyOnWriteArrayList<>();
        private final AtomicBoolean connected = new AtomicBoolean(false);
        private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
        private volatile HttpClient httpClient;
        private volatile Thread readerThread;
        private final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());

        public ServerConnection(String serverName, String streamUrl, String apiKey) {
            this.serverName = serverName;
            this.streamUrl = streamUrl;
            this.apiKey = apiKey;
        }

        public void ensureConnected() {
            if (connected.compareAndSet(false, true)) {
                shouldReconnect.set(true);
                startConnection();
            }
        }

        private void startConnection() {
            readerThread = new Thread(() -> {
                while (shouldReconnect.get()) {
                    try {
                        connectAndRead();
                    } catch (Exception e) {
                        log.warn("SSE connection error for {}: {}", serverName, e.getMessage());
                    }

                    if (shouldReconnect.get()) {
                        log.info("Reconnecting to {} in {} seconds...", serverName, RECONNECT_DELAY_SECONDS);
                        try {
                            Thread.sleep(RECONNECT_DELAY_SECONDS * 1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }, "sse-reader-" + serverName);
            readerThread.setDaemon(true);
            readerThread.start();
        }

        private void connectAndRead() throws IOException, InterruptedException {
            HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_1_1);

            httpClient = clientBuilder.build();

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(streamUrl))
                .timeout(Duration.ofMinutes(30))
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache");

            if (apiKey != null && !apiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
                log.info("SSE request includes Authorization header");
            } else {
                log.warn("SSE request without Authorization header for {}", serverName);
            }

            HttpRequest request = requestBuilder.GET().build();

            log.info("Connecting to SSE stream: {} (HTTP/1.1)", streamUrl);
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                throw new IOException("SSE connection failed with status: " + response.statusCode());
            }

            log.info("SSE connected to {} (status 200)", serverName);
            lastActivity.set(System.currentTimeMillis());

            InputStream bodyStream = response.body();
            log.info("SSE response body stream: {}", bodyStream != null ? "present" : "null");
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(bodyStream))) {
                log.info("Starting to read SSE stream for {}", serverName);
                
                String line;
                String eventId = null;
                StringBuilder dataBuilder = new StringBuilder();
                int lineCount = 0;

                while ((line = reader.readLine()) != null && shouldReconnect.get()) {
                    lineCount++;
                    lastActivity.set(System.currentTimeMillis());
                    log.info("SSE line {} received: {}", lineCount, line.length() > 500 ? line.substring(0, 500) + "..." : line);

                    if (line.startsWith(":")) {
                        log.debug("SSE keepalive line {}: {}", lineCount, line);
                        continue;
                    }
                    log.trace("SSE line {}: {}", lineCount, line);
                    if (line.startsWith("id:")) {
                        eventId = line.substring(3).trim();
                    } else if (line.startsWith("data:")) {
                        if (dataBuilder.length() > 0) {
                            dataBuilder.append("\n");
                        }
                        dataBuilder.append(line.substring(5).trim());
                    } else if (line.isEmpty() && dataBuilder.length() > 0) {
                        String data = dataBuilder.toString();
                        log.debug("SSE event received: id={}, data length={}", eventId, data.length());
                        processEvent(eventId, data);
                        eventId = null;
                        dataBuilder.setLength(0);
                    }
                }
                
                log.info("SSE stream ended for {}: line={}, shouldReconnect={}, lines read={}", 
                    serverName, line == null ? "null" : "not null", shouldReconnect.get(), lineCount);
            }
        }

        private void processEvent(String eventId, String data) {
            if (eventId == null) {
                eventId = eventBuffer.generateEventId();
            }

            String city = null;
            String eventType = null;

            try {
                JsonNode root = objectMapper.readTree(data);
                JsonNode params = root.path("params");
                JsonNode eventData = params.path("data");
                city = eventData.path("city").asText(null);
                eventType = eventData.path("type").asText(null);
            } catch (Exception e) {
                log.debug("Failed to parse event data: {}", e.getMessage());
            }

            BufferedEvent event = new BufferedEvent(eventId, eventType, city, data);
            eventBuffer.add(event);

            broadcast(event);
        }

        private void broadcast(BufferedEvent event) {
            for (ClientEntry entry : clients) {
                if (entry.isClosed()) {
                    continue;
                }
                if (matchesSubscription(event, entry.subscription)) {
                    try {
                        entry.client.sendEvent("message", event.data, event.eventId);
                    } catch (Exception e) {
                        log.debug("Failed to send event to client: {}", e.getMessage());
                        entry.markClosed();
                    }
                }
            }
        }

        public void addClient(SseClient client, ClientSubscription subscription) {
            clients.add(new ClientEntry(client, subscription));
            log.info("Client {} subscribed to {} (cities={}, types={})", 
                subscription.clientId, serverName, subscription.cities, subscription.eventTypes);
        }

        public void removeClient(String clientId) {
            clients.stream()
                .filter(e -> e.subscription.clientId.equals(clientId))
                .forEach(ClientEntry::markClosed);
            clients.removeIf(e -> e.subscription.clientId.equals(clientId));
            log.info("Client {} unsubscribed from {}", clientId, serverName);
        }

        public boolean isEmpty() {
            return clients.isEmpty();
        }

        public int clientCount() {
            return clients.size();
        }

        public boolean isConnected() {
            return connected.get();
        }

        public void stop() {
            shouldReconnect.set(false);
            connected.set(false);
            if (readerThread != null) {
                readerThread.interrupt();
            }
        }
    }

    private static class ClientEntry {
        final SseClient client;
        final ClientSubscription subscription;
        volatile boolean closed = false;

        ClientEntry(SseClient client, ClientSubscription subscription) {
            this.client = client;
            this.subscription = subscription;
        }

        void markClosed() {
            closed = true;
        }

        boolean isClosed() {
            return closed;
        }
    }

    public static class ClientSubscription {
        private final String clientId;
        private final Set<String> cities;
        private final Set<String> eventTypes;
        private String lastEventId;

        public ClientSubscription(String clientId) {
            this.clientId = clientId;
            this.cities = ConcurrentHashMap.newKeySet();
            this.eventTypes = ConcurrentHashMap.newKeySet();
        }

        public void addCity(String city) {
            if (city != null && !city.isBlank()) {
                cities.add(city.trim());
            }
        }

        public void addEventType(String eventType) {
            if (eventType != null && !eventType.isBlank()) {
                eventTypes.add(eventType.trim());
            }
        }

        public String getLastEventId() { return lastEventId; }
        public void setLastEventId(String lastEventId) { this.lastEventId = lastEventId; }
    }

    private static class BufferedEvent {
        final String eventId;
        final String eventType;
        final String city;
        final String data;

        BufferedEvent(String eventId, String eventType, String city, String data) {
            this.eventId = eventId;
            this.eventType = eventType;
            this.city = city;
            this.data = data;
        }
    }

    private static class EventBuffer {
        private final int maxSize;
        private final LinkedHashMap<String, BufferedEvent> events = new LinkedHashMap<>();
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private long sequence = 0;

        EventBuffer() {
            this(EVENT_BUFFER_SIZE);
        }

        EventBuffer(int maxSize) {
            this.maxSize = maxSize;
        }

        String generateEventId() {
            long ts = System.currentTimeMillis();
            String uuid8 = UUID.randomUUID().toString().substring(0, 8);
            long seq;
            lock.writeLock().lock();
            try {
                seq = ++sequence;
            } finally {
                lock.writeLock().unlock();
            }
            return ts + "-" + uuid8 + "-" + seq;
        }

        void add(BufferedEvent event) {
            lock.writeLock().lock();
            try {
                if (events.size() >= maxSize) {
                    String oldestKey = events.keySet().iterator().next();
                    events.remove(oldestKey);
                }
                events.put(event.eventId, event);
            } finally {
                lock.writeLock().unlock();
            }
        }

        List<BufferedEvent> getAfter(String eventId) {
            List<BufferedEvent> result = new ArrayList<>();
            lock.readLock().lock();
            try {
                boolean found = eventId == null || eventId.isEmpty();
                for (Map.Entry<String, BufferedEvent> entry : events.entrySet()) {
                    if (found) {
                        result.add(entry.getValue());
                    } else if (entry.getKey().equals(eventId)) {
                        found = true;
                    }
                }
            } finally {
                lock.readLock().unlock();
            }
            return result;
        }
    }
}
