package com.example.deepseek.app;

import com.example.deepseek.client.DeepSeekClient;
import com.example.deepseek.dto.Message;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Веб-приложение для DeepSeek CLI с интерфейсом в браузере.
 */
public class WebApp {

    private static final int DEFAULT_PORT = 8080;
    private static final String API_KEY_ENV = "DEEPSEEK_API_KEY";
    
    private static DeepSeekClient client;
    private static ObjectMapper objectMapper = new ObjectMapper();
    private static List<ChatMessage> chatHistory = new ArrayList<>();
    private static int currentMode = 2; // 1 = Tester, 2 = Helper

    public static void main(String[] args) throws IOException {
        String apiKey = System.getenv(API_KEY_ENV);
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Ошибка: Не установлена переменная окружения " + API_KEY_ENV);
            System.err.println("Установите её командой: set " + API_KEY_ENV + "=your_api_key");
            System.exit(1);
        }

        client = new DeepSeekClient(apiKey);

        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("Неверный порт, использую стандартный: " + DEFAULT_PORT);
            }
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        
        // API endpoints
        server.createContext("/api/chat", new ChatHandler());
        server.createContext("/api/clear", new ClearHandler());
        server.createContext("/api/mode", new ModeHandler());
        server.createContext("/api/history", new HistoryHandler());
        server.createContext("/api/info", new InfoHandler());
        server.createContext("/api/system", new SystemHandler());
        server.createContext("/api/limited", new LimitedHandler());
        server.createContext("/api/settings", new SettingsHandler());
        
        // Статические файлы
        server.createContext("/", new StaticFileHandler());
        
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        String url = "http://localhost:" + port;
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║           DeepSeek Web Interface - Запущен!              ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.println("║  Откройте в браузере: " + url + "              ║");
        System.out.println("║  Нажмите Ctrl+C для остановки сервера                    ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");

        // Открываем браузер
        openBrowser(url);
    }

    private static void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;
            
            if (os.contains("win")) {
                // Windows - открываем в Chrome если есть, иначе в браузере по умолчанию
                pb = new ProcessBuilder("cmd", "/c", "start", "chrome", url);
                try {
                    pb.start();
                } catch (IOException e) {
                    // Если Chrome не найден, открываем в браузере по умолчанию
                    pb = new ProcessBuilder("cmd", "/c", "start", url);
                    pb.start();
                }
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", "-a", "Google Chrome", url);
                try {
                    pb.start();
                } catch (IOException e) {
                    pb = new ProcessBuilder("open", url);
                    pb.start();
                }
            } else {
                pb = new ProcessBuilder("xdg-open", url);
                pb.start();
            }
        } catch (Exception e) {
            System.out.println("Не удалось открыть браузер автоматически. Откройте: " + url);
        }
    }

    // Handler для чата
    static class ChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // CORS headers
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            try {
                // Читаем тело запроса
                InputStream is = exchange.getRequestBody();
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                
                // Парсим JSON
                Map<String, String> request = objectMapper.readValue(body, Map.class);
                String message = request.get("message");

                if (message == null || message.isBlank()) {
                    sendError(exchange, 400, "Сообщение не может быть пустым");
                    return;
                }

                // Отправляем запрос к DeepSeek API
                String response = client.chat(message);

                // Сохраняем в историю
                chatHistory.add(new ChatMessage("user", message));
                chatHistory.add(new ChatMessage("assistant", response));

                // Отправляем ответ
                Map<String, Object> responseMap = new HashMap<>();
                responseMap.put("response", response);
                responseMap.put("success", true);

                sendJsonResponse(exchange, 200, responseMap);

            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, 500, "Ошибка: " + e.getMessage());
            }
        }
    }

    // Handler для очистки истории
    static class ClearHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            chatHistory.clear();
            client.clearHistory();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "История очищена");
            
            sendJsonResponse(exchange, 200, response);
        }
    }

    // Handler для смены режима
    static class ModeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            if ("GET".equals(exchange.getRequestMethod())) {
                // Возвращаем текущий режим
                Map<String, Object> response = new HashMap<>();
                response.put("mode", currentMode);
                response.put("modeName", currentMode == 1 ? "Tester" : "Helper");
                sendJsonResponse(exchange, 200, response);
                return;
            }

            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    InputStream is = exchange.getRequestBody();
                    String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    
                    Map<String, Integer> request = objectMapper.readValue(body, Map.class);
                    Integer mode = request.get("mode");

                    if (mode == null || (mode != 1 && mode != 2)) {
                        sendError(exchange, 400, "Режим должен быть 1 (Tester) или 2 (Helper)");
                        return;
                    }

                    currentMode = mode;
                    client.setSystemMessage(mode);
                    chatHistory.clear();
                    
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", true);
                    response.put("mode", currentMode);
                    response.put("modeName", currentMode == 1 ? "Tester" : "Helper");
                    response.put("message", "Режим изменён, история очищена");
                    
                    sendJsonResponse(exchange, 200, response);
                } catch (Exception e) {
                    sendError(exchange, 500, "Ошибка: " + e.getMessage());
                }
            }
        }
    }

    // Handler для получения истории
    static class HistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            Map<String, Object> response = new HashMap<>();
            response.put("history", chatHistory);
            response.put("mode", currentMode);
            response.put("modeName", currentMode == 1 ? "Tester" : "Helper");
            
            sendJsonResponse(exchange, 200, response);
        }
    }

    // Handler для информации о системе
    static class InfoHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            Map<String, Object> info = new HashMap<>();
            info.put("javaVersion", System.getProperty("java.version"));
            info.put("osName", System.getProperty("os.name"));
            info.put("osVersion", System.getProperty("os.version"));
            info.put("userDir", System.getProperty("user.dir"));
            info.put("fileEncoding", System.getProperty("file.encoding"));
            info.put("userName", System.getProperty("user.name"));
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("info", info);
            
            sendJsonResponse(exchange, 200, response);
        }
    }

    // Handler для получения системного промпта
    static class SystemHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            String systemPrompt = client.getCurrentSystemMessage();
            String modeDescription = getModeDescription(systemPrompt);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("systemPrompt", systemPrompt);
            response.put("modeDescription", modeDescription);
            
            sendJsonResponse(exchange, 200, response);
        }
    }

    // Handler для ограниченных запросов
    static class LimitedHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            try {
                InputStream is = exchange.getRequestBody();
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                
                Map<String, String> request = objectMapper.readValue(body, Map.class);
                String message = request.get("message");

                if (message == null || message.isBlank()) {
                    sendError(exchange, 400, "Сообщение не может быть пустым");
                    return;
                }

                // Отправляем ограниченный запрос
                String response = client.chatLimited(message);

                Map<String, Object> responseMap = new HashMap<>();
                responseMap.put("response", response);
                responseMap.put("success", true);
                responseMap.put("maxTokens", client.getMaxTokens());
                responseMap.put("limited", true);

                sendJsonResponse(exchange, 200, responseMap);

            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, 500, "Ошибка: " + e.getMessage());
            }
        }
    }

    // Handler для настроек
    static class SettingsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            if ("GET".equals(exchange.getRequestMethod())) {
                // Возвращаем текущие настройки
                Map<String, Object> settings = new HashMap<>();
                settings.put("maxTokens", client.getMaxTokens());
                settings.put("maxTokensEnabled", client.isMaxTokensEnabled());
                settings.put("stopSequences", client.getStopSequences());
                settings.put("stopSequencesEnabled", client.isStopSequencesEnabled());
                settings.put("temperature", client.getTemperature());
                settings.put("temperatureEnabled", client.isTemperatureEnabled());
                settings.put("mode", currentMode);
                settings.put("modeName", currentMode == 1 ? "Tester" : "Helper");
                settings.put("modeDescription", getModeDescription(client.getCurrentSystemMessage()));
                settings.put("systemPrompt", client.getCurrentSystemMessage());
                
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("settings", settings);
                
                sendJsonResponse(exchange, 200, response);
                return;
            }

            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    InputStream is = exchange.getRequestBody();
                    String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    
                    Map<String, Object> request = objectMapper.readValue(body, Map.class);
                    
                    String param = (String) request.get("param");
                    Object value = request.get("value");

                    if (param == null || value == null) {
                        sendError(exchange, 400, "Укажите параметр и значение");
                        return;
                    }

                    Map<String, Object> response = new HashMap<>();
                    
                    switch (param.toLowerCase()) {
                        case "max_tokens" -> {
                            int tokens = ((Number) value).intValue();
                            client.setMaxTokens(tokens);
                            response.put("success", true);
                            response.put("message", "Максимум токенов установлен: " + tokens);
                            response.put("maxTokens", tokens);
                        }
                        case "max_tokens_enabled" -> {
                            boolean enabled = Boolean.TRUE.equals(value);
                            client.setMaxTokensEnabled(enabled);
                            response.put("success", true);
                            response.put("message", enabled ? "Лимит токенов включён" : "Лимит токенов выключен");
                            response.put("maxTokensEnabled", enabled);
                        }
                        case "stop" -> {
                            List<String> stopList = new ArrayList<>();
                            if (value instanceof List) {
                                for (Object seq : (List<?>) value) {
                                    String processed = seq.toString().replace("\\n", "\n");
                                    stopList.add(processed);
                                }
                            } else if (value instanceof String) {
                                String cleanValue = ((String) value).replaceAll("^[\"']|[\"']$", "");
                                String[] sequences = cleanValue.split(",");
                                for (String seq : sequences) {
                                    String processed = seq.trim().replace("\\n", "\n");
                                    if (!processed.isEmpty()) {
                                        stopList.add(processed);
                                    }
                                }
                            }
                            client.setStopSequences(stopList);
                            response.put("success", true);
                            response.put("message", "Стоп-последовательности установлены");
                            response.put("stopSequences", stopList);
                        }
                        case "stop_enabled" -> {
                            boolean enabled = Boolean.TRUE.equals(value);
                            client.setStopSequencesEnabled(enabled);
                            response.put("success", true);
                            response.put("message", enabled ? "Стоп-последовательности включены" : "Стоп-последовательности выключены");
                            response.put("stopSequencesEnabled", enabled);
                        }
                        case "temperature" -> {
                            double temp = ((Number) value).doubleValue();
                            client.setTemperature(temp);
                            response.put("success", true);
                            response.put("message", "Temperature установлена: " + temp);
                            response.put("temperature", temp);
                        }
                        case "temperature_enabled" -> {
                            boolean enabled = Boolean.TRUE.equals(value);
                            client.setTemperatureEnabled(enabled);
                            response.put("success", true);
                            response.put("message", enabled ? "Temperature включена" : "Temperature выключена");
                            response.put("temperatureEnabled", enabled);
                        }
                        case "system_prompt" -> {
                            String systemPrompt = value.toString();
                            client.setSystemMessage(systemPrompt);
                            chatHistory.clear();
                            response.put("success", true);
                            response.put("message", "Системный промпт обновлён");
                            response.put("systemPrompt", systemPrompt);
                        }
                        default -> {
                            sendError(exchange, 400, "Неизвестный параметр: " + param);
                            return;
                        }
                    }
                    
                    sendJsonResponse(exchange, 200, response);
                } catch (Exception e) {
                    sendError(exchange, 500, "Ошибка: " + e.getMessage());
                }
            }
        }
    }

    private static String getModeDescription(String systemMessage) {
        if (systemMessage.contains("тестировщик")) {
            return "Тестировщик 🧪";
        } else {
            return "Помощник 💡";
        }
    }

    // Handler для статических файлов
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            
            if ("/".equals(path)) {
                path = "/index.html";
            }

            // Определяем MIME-тип
            String contentType = getContentType(path);
            
            // Встраиваем HTML/CSS/JS прямо в код
            byte[] content = getStaticContent(path);
            
            if (content == null) {
                String notFound = "404 Not Found";
                exchange.sendResponseHeaders(404, notFound.length());
                OutputStream os = exchange.getResponseBody();
                os.write(notFound.getBytes(StandardCharsets.UTF_8));
                os.close();
                return;
            }

            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, content.length);
            OutputStream os = exchange.getResponseBody();
            os.write(content);
            os.close();
        }

        private String getContentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=UTF-8";
            if (path.endsWith(".css")) return "text/css; charset=UTF-8";
            if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
            if (path.endsWith(".json")) return "application/json; charset=UTF-8";
            if (path.endsWith(".png")) return "image/png";
            if (path.endsWith(".ico")) return "image/x-icon";
            return "text/plain; charset=UTF-8";
        }

        private byte[] getStaticContent(String path) {
            String content = null;
            
            if ("/index.html".equals(path)) {
                content = INDEX_HTML;
            } else if ("/style.css".equals(path)) {
                content = STYLE_CSS;
            } else if ("/app.js".equals(path)) {
                content = APP_JS;
            }

            return content != null ? content.getBytes(StandardCharsets.UTF_8) : null;
        }
    }

    // Вспомогательные методы
    private static void sendJsonResponse(HttpExchange exchange, int statusCode, Map<String, Object> response) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        String json = objectMapper.writeValueAsString(response);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("error", message);
        sendJsonResponse(exchange, statusCode, response);
    }

    // Класс для хранения сообщений чата
    static class ChatMessage {
        public String role;
        public String content;

        public ChatMessage() {}

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    // ==================== СТАТИЧЕСКИЕ ФАЙЛЫ ====================

    private static final String INDEX_HTML = """
<!DOCTYPE html>
<html lang="ru">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>DeepSeek Chat</title>
    <link rel="stylesheet" href="style.css">
    <!-- Marked.js для рендеринга Markdown -->
    <script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
</head>
<body>
    <div class="container">
        <header>
            <div class="logo">
                <span class="logo-icon">🤖</span>
                <h1>DeepSeek Chat</h1>
            </div>
            <div class="controls">
                <select id="modeSelect">
                    <option value="2">🛠️ Помощник</option>
                    <option value="1">🧪 Тестировщик</option>
                </select>
                <button id="settingsBtn" class="btn-secondary" title="Настройки">
                    ⚙️ Настройки
                </button>
                <button id="clearBtn" class="btn-secondary" title="Очистить историю">
                    🗑️ Очистить
                </button>
            </div>
        </header>

        <main id="chatContainer">
            <div class="welcome-message">
                <div class="welcome-icon">👋</div>
                <h2>Привет! Я DeepSeek AI</h2>
                <p>Задайте мне любой вопрос, и я постараюсь помочь!</p>
            </div>
        </main>

        <footer>
            <div class="input-container">
                <textarea 
                    id="messageInput" 
                    placeholder="Введите сообщение..." 
                    rows="1"
                    autofocus
                ></textarea>
                <button id="sendBtn" class="btn-primary">
                    <span class="send-icon">➤</span>
                </button>
            </div>
            <div class="status-bar">
                <span id="statusText">Готов к работе</span>
                <span id="modeText">Режим: Помощник</span>
            </div>
        </footer>
    </div>

    <!-- Модальное окно настроек -->
    <div id="settingsModal" class="modal">
        <div class="modal-content">
            <div class="modal-header">
                <h2>⚙️ Настройки</h2>
                <button class="modal-close" id="closeSettings">&times;</button>
            </div>
            <div class="modal-body">
                <div class="settings-tabs">
                    <button class="tab-btn active" data-tab="general">📊 Основные</button>
                    <button class="tab-btn" data-tab="system">🎭 Системный промпт</button>
                    <button class="tab-btn" data-tab="info">ℹ️ Информация</button>
                </div>
                
                <div class="tab-content active" id="tab-general">
                    <div class="setting-group">
                        <div class="setting-row">
                            <label for="maxTokensInput">Максимальное количество токенов в ответе:</label>
                            <label class="toggle-switch">
                                <input type="checkbox" id="maxTokensEnabled">
                                <span class="toggle-slider"></span>
                            </label>
                        </div>
                        <div class="setting-input-row">
                            <input type="number" id="maxTokensInput" min="1" max="32000" value="200">
                            <button class="btn-small" id="saveMaxTokens">Сохранить</button>
                        </div>
                        <p class="setting-hint">Ограничивает длину ответа ИИ. Чем больше значение, тем длиннее может быть ответ.</p>
                    </div>
                    <div class="setting-group">
                        <div class="setting-row">
                            <label for="stopSequencesInput">Стоп-последовательности:</label>
                            <label class="toggle-switch">
                                <input type="checkbox" id="stopSequencesEnabled">
                                <span class="toggle-slider"></span>
                            </label>
                        </div>
                        <div class="setting-input-row">
                            <input type="text" id="stopSequencesInput" placeholder="\\n\\n,---,###">
                            <button class="btn-small" id="saveStopSequences">Сохранить</button>
                        </div>
                        <p class="setting-hint">ИИ остановит генерацию при встрече с этими последовательностями.</p>
                    </div>
                    <div class="setting-group">
                        <div class="setting-row">
                            <label for="temperatureInput">Temperature (0-2):</label>
                            <label class="toggle-switch">
                                <input type="checkbox" id="temperatureEnabled">
                                <span class="toggle-slider"></span>
                            </label>
                        </div>
                        <div class="setting-input-row">
                            <input type="range" id="temperatureInput" min="0" max="2" step="0.1" value="1.0">
                            <span id="temperatureValue">1.0</span>
                            <button class="btn-small" id="saveTemperature">Сохранить</button>
                        </div>
                        <p class="setting-hint">Низкие значения = более предсказуемый ответ, высокие = более креативный.</p>
                    </div>
                </div>
                
                <div class="tab-content" id="tab-system">
                    <div class="system-prompt-container">
                        <h3>🎭 Системный промпт</h3>
                        <textarea id="systemPromptInput" class="system-prompt-textarea" placeholder="Введите системный промпт..."></textarea>
                        <div class="system-prompt-buttons">
                            <button class="btn-small" id="saveSystemPrompt">💾 Сохранить</button>
                            <button class="btn-small btn-secondary" id="resetSystemPrompt">🔄 Сбросить</button>
                        </div>
                        <p class="mode-info">Режим: <span id="systemModeInfo">Помощник</span></p>
                    </div>
                </div>
                
                <div class="tab-content" id="tab-info">
                    <div class="info-container">
                        <h3>ℹ️ Информация о системе</h3>
                        <div class="info-grid">
                            <div class="info-item">
                                <span class="info-label">☕ Java версия:</span>
                                <span class="info-value" id="infoJava">-</span>
                            </div>
                            <div class="info-item">
                                <span class="info-label">💻 ОС:</span>
                                <span class="info-value" id="infoOs">-</span>
                            </div>
                            <div class="info-item">
                                <span class="info-label">📁 Рабочая директория:</span>
                                <span class="info-value" id="infoDir">-</span>
                            </div>
                            <div class="info-item">
                                <span class="info-label">🔤 Кодировка:</span>
                                <span class="info-value" id="infoEncoding">-</span>
                            </div>
                            <div class="info-item">
                                <span class="info-label">👤 Пользователь:</span>
                                <span class="info-value" id="infoUser">-</span>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </div>

    <script src="app.js"></script>
</body>
</html>
""";

    private static final String STYLE_CSS = """
* {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
}

:root {
    --primary-color: #4f46e5;
    --primary-hover: #4338ca;
    --secondary-color: #6b7280;
    --bg-color: #f3f4f6;
    --chat-bg: #ffffff;
    --user-bg: #4f46e5;
    --assistant-bg: #f9fafb;
    --text-color: #1f2937;
    --text-light: #6b7280;
    --border-color: #e5e7eb;
    --shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
    --shadow-lg: 0 4px 6px rgba(0, 0, 0, 0.1);
}

body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
    background: var(--bg-color);
    color: var(--text-color);
    height: 100vh;
    overflow: hidden;
}

.container {
    display: flex;
    flex-direction: column;
    height: 100vh;
    max-width: 1200px;
    margin: 0 auto;
    background: var(--chat-bg);
    box-shadow: var(--shadow-lg);
}

/* Header */
header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 1rem 1.5rem;
    background: linear-gradient(135deg, var(--primary-color), #7c3aed);
    color: white;
    box-shadow: var(--shadow);
}

.logo {
    display: flex;
    align-items: center;
    gap: 0.75rem;
}

.logo-icon {
    font-size: 2rem;
}

.logo h1 {
    font-size: 1.5rem;
    font-weight: 600;
}

.controls {
    display: flex;
    gap: 0.75rem;
    align-items: center;
}

select {
    padding: 0.5rem 1rem;
    border: none;
    border-radius: 0.5rem;
    background: rgba(255, 255, 255, 0.2);
    color: white;
    font-size: 0.9rem;
    cursor: pointer;
    outline: none;
}

select option {
    background: var(--primary-color);
    color: white;
}

.btn-secondary {
    padding: 0.5rem 1rem;
    border: none;
    border-radius: 0.5rem;
    background: rgba(255, 255, 255, 0.2);
    color: white;
    font-size: 0.9rem;
    cursor: pointer;
    transition: background 0.2s;
}

.btn-secondary:hover {
    background: rgba(255, 255, 255, 0.3);
}

/* Chat Container */
main {
    flex: 1;
    overflow-y: auto;
    padding: 1.5rem;
    display: flex;
    flex-direction: column;
    gap: 1rem;
}

.welcome-message {
    text-align: center;
    padding: 3rem;
    color: var(--text-light);
}

.welcome-icon {
    font-size: 4rem;
    margin-bottom: 1rem;
}

.welcome-message h2 {
    font-size: 1.5rem;
    margin-bottom: 0.5rem;
    color: var(--text-color);
}

/* Messages */
.message {
    display: flex;
    gap: 0.75rem;
    max-width: 85%;
    animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
    from {
        opacity: 0;
        transform: translateY(10px);
    }
    to {
        opacity: 1;
        transform: translateY(0);
    }
}

.message.user {
    align-self: flex-end;
    flex-direction: row-reverse;
}

.message-avatar {
    width: 36px;
    height: 36px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 1.2rem;
    flex-shrink: 0;
}

.message.user .message-avatar {
    background: var(--primary-color);
}

.message.assistant .message-avatar {
    background: linear-gradient(135deg, #10b981, #059669);
}

.message.limited .message-avatar {
    background: linear-gradient(135deg, #f59e0b, #d97706);
}

.message-content {
    padding: 0.75rem 1rem;
    border-radius: 1rem;
    line-height: 1.5;
    word-wrap: break-word;
}

/* Markdown стили */
.message-content p {
    margin: 0.5rem 0;
}

.message-content p:first-child {
    margin-top: 0;
}

.message-content p:last-child {
    margin-bottom: 0;
}

.message-content code {
    background: rgba(0, 0, 0, 0.1);
    padding: 0.2rem 0.4rem;
    border-radius: 0.25rem;
    font-family: 'Consolas', 'Monaco', monospace;
    font-size: 0.9em;
}

.message.user .message-content code {
    background: rgba(255, 255, 255, 0.2);
}

.message-content pre {
    background: #1e1e1e;
    color: #d4d4d4;
    padding: 1rem;
    border-radius: 0.5rem;
    overflow-x: auto;
    margin: 0.75rem 0;
}

.message-content pre code {
    background: none;
    padding: 0;
    color: inherit;
}

.message-content ul, .message-content ol {
    margin: 0.5rem 0;
    padding-left: 1.5rem;
}

.message-content li {
    margin: 0.25rem 0;
}

.message-content blockquote {
    border-left: 3px solid var(--primary-color);
    padding-left: 1rem;
    margin: 0.75rem 0;
    color: var(--text-light);
    font-style: italic;
}

.message-content h1, .message-content h2, .message-content h3,
.message-content h4, .message-content h5, .message-content h6 {
    margin: 1rem 0 0.5rem 0;
    font-weight: 600;
}

.message-content h1 { font-size: 1.5rem; }
.message-content h2 { font-size: 1.3rem; }
.message-content h3 { font-size: 1.1rem; }

.message-content a {
    color: var(--primary-color);
    text-decoration: none;
}

.message-content a:hover {
    text-decoration: underline;
}

.message-content table {
    border-collapse: collapse;
    margin: 0.75rem 0;
    width: 100%;
}

.message-content th, .message-content td {
    border: 1px solid var(--border-color);
    padding: 0.5rem;
    text-align: left;
}

.message-content th {
    background: var(--bg-color);
    font-weight: 600;
}

.message-content hr {
    border: none;
    border-top: 1px solid var(--border-color);
    margin: 1rem 0;
}

/* Эффект печатания */
.typing-cursor {
    display: inline-block;
    width: 2px;
    height: 1em;
    background: var(--primary-color);
    margin-left: 2px;
    animation: blink 1s infinite;
    vertical-align: text-bottom;
}

@keyframes blink {
    0%, 50% { opacity: 1; }
    51%, 100% { opacity: 0; }
}

.message.user .message-content {
    background: var(--user-bg);
    color: white;
    border-bottom-right-radius: 0.25rem;
}

.message.assistant .message-content {
    background: var(--assistant-bg);
    border: 1px solid var(--border-color);
    border-bottom-left-radius: 0.25rem;
}

.message.limited .message-content {
    background: #fef3c7;
    border: 1px solid #f59e0b;
    border-bottom-left-radius: 0.25rem;
}

.message-label {
    font-size: 0.75rem;
    color: var(--text-light);
    margin-bottom: 0.25rem;
}

/* Typing indicator */
.typing {
    display: flex;
    gap: 0.25rem;
    padding: 0.75rem 1rem;
    background: var(--assistant-bg);
    border: 1px solid var(--border-color);
    border-radius: 1rem;
    border-bottom-left-radius: 0.25rem;
}

.typing span {
    width: 8px;
    height: 8px;
    background: var(--secondary-color);
    border-radius: 50%;
    animation: typing 1.4s infinite;
}

.typing span:nth-child(2) {
    animation-delay: 0.2s;
}

.typing span:nth-child(3) {
    animation-delay: 0.4s;
}

@keyframes typing {
    0%, 60%, 100% {
        transform: translateY(0);
    }
    30% {
        transform: translateY(-4px);
    }
}

/* Footer */
footer {
    padding: 1rem 1.5rem;
    background: var(--chat-bg);
    border-top: 1px solid var(--border-color);
}

.input-container {
    display: flex;
    gap: 0.75rem;
    align-items: flex-end;
}

textarea {
    flex: 1;
    padding: 0.75rem 1rem;
    border: 2px solid var(--border-color);
    border-radius: 1.5rem;
    font-size: 1rem;
    font-family: inherit;
    resize: none;
    outline: none;
    transition: border-color 0.2s;
    max-height: 150px;
    line-height: 1.5;
    overflow: hidden;
}

textarea:focus {
    border-color: var(--primary-color);
}

.btn-primary {
    width: 48px;
    height: 48px;
    border: none;
    border-radius: 50%;
    background: var(--primary-color);
    color: white;
    font-size: 1.2rem;
    cursor: pointer;
    transition: all 0.2s;
    display: flex;
    align-items: center;
    justify-content: center;
}

.btn-primary:hover {
    background: var(--primary-hover);
    transform: scale(1.05);
}

.btn-primary:disabled {
    background: var(--secondary-color);
    cursor: not-allowed;
    transform: none;
}

.send-icon {
    margin-left: 2px;
}

.status-bar {
    display: flex;
    justify-content: space-between;
    margin-top: 0.5rem;
    font-size: 0.8rem;
    color: var(--text-light);
}

/* Modal */
.modal {
    display: none;
    position: fixed;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    background: rgba(0, 0, 0, 0.5);
    z-index: 1000;
    justify-content: center;
    align-items: center;
}

.modal.active {
    display: flex;
}

.modal-content {
    background: white;
    border-radius: 1rem;
    width: 90%;
    max-width: 600px;
    max-height: 80vh;
    overflow: hidden;
    display: flex;
    flex-direction: column;
    box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1);
}

.modal-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 1rem 1.5rem;
    border-bottom: 1px solid var(--border-color);
}

.modal-header h2 {
    font-size: 1.25rem;
}

.modal-close {
    background: none;
    border: none;
    font-size: 1.5rem;
    cursor: pointer;
    color: var(--text-light);
    padding: 0.25rem;
}

.modal-close:hover {
    color: var(--text-color);
}

.modal-body {
    padding: 1rem;
    overflow-y: auto;
}

/* Tabs */
.settings-tabs {
    display: flex;
    gap: 0.5rem;
    margin-bottom: 1rem;
    flex-wrap: wrap;
}

.tab-btn {
    padding: 0.5rem 1rem;
    border: none;
    background: var(--bg-color);
    border-radius: 0.5rem;
    cursor: pointer;
    font-size: 0.9rem;
    transition: all 0.2s;
}

.tab-btn:hover {
    background: var(--border-color);
}

.tab-btn.active {
    background: var(--primary-color);
    color: white;
}

.tab-content {
    display: none;
}

.tab-content.active {
    display: block;
}

/* Settings */
.setting-group {
    margin-bottom: 1rem;
}

.setting-group label {
    display: block;
    margin-bottom: 0.5rem;
    font-weight: 500;
}

.setting-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 0.5rem;
}

.setting-row label {
    margin-bottom: 0;
}

.setting-input-row {
    display: flex;
    gap: 0.5rem;
    align-items: center;
}

.setting-input-row input[type="number"],
.setting-input-row input[type="text"] {
    flex: 1;
}

.setting-input-row input[type="range"] {
    flex: 1;
    margin-right: 0.5rem;
}

#temperatureValue {
    min-width: 2.5rem;
    text-align: center;
    font-weight: 500;
}

.setting-group input[type="number"],
.setting-group input[type="text"],
.setting-group textarea {
    width: 100%;
    padding: 0.75rem;
    border: 2px solid var(--border-color);
    border-radius: 0.5rem;
    font-size: 1rem;
    margin-bottom: 0.5rem;
}

.setting-group input:focus,
.setting-group textarea:focus {
    border-color: var(--primary-color);
    outline: none;
}

/* Toggle Switch */
.toggle-switch {
    position: relative;
    display: inline-block;
    width: 50px;
    height: 26px;
}

.toggle-switch input {
    opacity: 0;
    width: 0;
    height: 0;
}

.toggle-slider {
    position: absolute;
    cursor: pointer;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background-color: #ccc;
    transition: .3s;
    border-radius: 26px;
}

.toggle-slider:before {
    position: absolute;
    content: "";
    height: 20px;
    width: 20px;
    left: 3px;
    bottom: 3px;
    background-color: white;
    transition: .3s;
    border-radius: 50%;
}

.toggle-switch input:checked + .toggle-slider {
    background-color: var(--primary-color);
}

.toggle-switch input:checked + .toggle-slider:before {
    transform: translateX(24px);
}

.btn-small {
    padding: 0.5rem 1rem;
    border: none;
    border-radius: 0.5rem;
    background: var(--primary-color);
    color: white;
    cursor: pointer;
    font-size: 0.9rem;
}

.btn-small:hover {
    background: var(--primary-hover);
}

.btn-small.btn-secondary {
    background: var(--secondary-color);
}

.btn-small.btn-secondary:hover {
    background: #4b5563;
}

/* Setting hints */
.setting-hint {
    font-size: 0.85rem;
    color: var(--text-light);
    margin-top: 0.25rem;
    margin-bottom: 0;
}

/* Limited info */
.limited-info {
    background: #fef3c7;
    padding: 1rem;
    border-radius: 0.5rem;
    margin-bottom: 1rem;
}

.limited-info h3 {
    margin-bottom: 0.5rem;
}

.limited-info p {
    color: var(--text-light);
    font-size: 0.9rem;
}

.current-settings {
    margin-top: 0.75rem;
    padding-top: 0.75rem;
    border-top: 1px solid #f59e0b;
}

.current-settings p {
    margin: 0.25rem 0;
}

/* System prompt */
.system-prompt-container {
    background: var(--bg-color);
    padding: 1rem;
    border-radius: 0.5rem;
}

.system-prompt-container h3 {
    margin-bottom: 0.75rem;
}

.system-prompt-textarea {
    width: 100%;
    min-height: 150px;
    padding: 1rem;
    border-radius: 0.5rem;
    border: 2px solid var(--border-color);
    font-family: monospace;
    font-size: 0.9rem;
    resize: vertical;
    margin-bottom: 0.75rem;
}

.system-prompt-textarea:focus {
    border-color: var(--primary-color);
    outline: none;
}

.system-prompt-buttons {
    display: flex;
    gap: 0.5rem;
    margin-bottom: 0.75rem;
}

.mode-info {
    margin-top: 0.75rem;
    font-size: 0.9rem;
    color: var(--text-light);
}

/* Info */
.info-container {
    background: var(--bg-color);
    padding: 1rem;
    border-radius: 0.5rem;
}

.info-container h3 {
    margin-bottom: 0.75rem;
}

.info-grid {
    display: grid;
    gap: 0.75rem;
}

.info-item {
    display: flex;
    justify-content: space-between;
    padding: 0.5rem;
    background: white;
    border-radius: 0.5rem;
}

.info-label {
    font-weight: 500;
}

.info-value {
    color: var(--text-light);
    word-break: break-all;
    text-align: right;
    max-width: 60%;
}

/* Scrollbar */
::-webkit-scrollbar {
    width: 8px;
}

::-webkit-scrollbar-track {
    background: transparent;
}

::-webkit-scrollbar-thumb {
    background: var(--border-color);
    border-radius: 4px;
}

::-webkit-scrollbar-thumb:hover {
    background: var(--secondary-color);
}

/* Responsive */
@media (max-width: 768px) {
    header {
        flex-direction: column;
        gap: 1rem;
        text-align: center;
    }

    .message {
        max-width: 95%;
    }

    .logo h1 {
        font-size: 1.25rem;
    }
    
    .settings-tabs {
        flex-direction: column;
    }
    
    .tab-btn {
        width: 100%;
    }
}
""";

    private static final String APP_JS = """
// DOM Elements
const chatContainer = document.getElementById('chatContainer');
const messageInput = document.getElementById('messageInput');
const sendBtn = document.getElementById('sendBtn');
const clearBtn = document.getElementById('clearBtn');
const modeSelect = document.getElementById('modeSelect');
const statusText = document.getElementById('statusText');
const modeText = document.getElementById('modeText');
const settingsBtn = document.getElementById('settingsBtn');
const settingsModal = document.getElementById('settingsModal');
const closeSettings = document.getElementById('closeSettings');

// State
let isLoading = false;

// Настройка marked.js для рендеринга Markdown
if (typeof marked !== 'undefined') {
    marked.setOptions({
        breaks: true,
        gfm: true
    });
}

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    loadHistory();
    loadMode();
    loadSettings();
    setupEventListeners();
});

function setupEventListeners() {
    sendBtn.addEventListener('click', sendMessage);
    
    messageInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });

    // Auto-resize textarea
    messageInput.addEventListener('input', () => {
        messageInput.style.height = 'auto';
        messageInput.style.height = Math.min(messageInput.scrollHeight, 150) + 'px';
    });

    clearBtn.addEventListener('click', clearHistory);
    modeSelect.addEventListener('change', changeMode);
    
    // Settings modal
    settingsBtn.addEventListener('click', () => {
        settingsModal.classList.add('active');
        loadSettings();
        loadSystemInfo();
    });
    
    closeSettings.addEventListener('click', () => {
        settingsModal.classList.remove('active');
    });
    
    settingsModal.addEventListener('click', (e) => {
        if (e.target === settingsModal) {
            settingsModal.classList.remove('active');
        }
    });
    
    // Tabs
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
            document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
            btn.classList.add('active');
            document.getElementById('tab-' + btn.dataset.tab).classList.add('active');
        });
    });
    
    // Settings handlers
    document.getElementById('saveMaxTokens').addEventListener('click', saveMaxTokens);
    document.getElementById('saveStopSequences').addEventListener('click', saveStopSequences);
    document.getElementById('saveTemperature').addEventListener('click', saveTemperature);
    
    // Toggle handlers
    document.getElementById('maxTokensEnabled').addEventListener('change', toggleMaxTokens);
    document.getElementById('stopSequencesEnabled').addEventListener('change', toggleStopSequences);
    document.getElementById('temperatureEnabled').addEventListener('change', toggleTemperature);
    
    // Temperature slider
    document.getElementById('temperatureInput').addEventListener('input', (e) => {
        document.getElementById('temperatureValue').textContent = e.target.value;
    });
    
    // System prompt handlers
    document.getElementById('saveSystemPrompt').addEventListener('click', saveSystemPrompt);
    document.getElementById('resetSystemPrompt').addEventListener('click', resetSystemPrompt);
}

async function sendMessage() {
    const message = messageInput.value.trim();
    if (!message || isLoading) return;

    // Clear input
    messageInput.value = '';
    messageInput.style.height = 'auto';

    // Add user message to UI
    addMessage('user', message);
    
    // Show typing indicator
    showTyping();
    setLoading(true);
    statusText.textContent = 'Отправка запроса...';

    try {
        const response = await fetch('/api/chat', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ message })
        });

        const data = await response.json();
        
        hideTyping();

        if (data.success) {
            // Добавляем сообщение с эффектом печатания
            await addMessageWithTyping('assistant', data.response);
            statusText.textContent = 'Готов к работе';
        } else {
            addMessage('assistant', '❌ Ошибка: ' + (data.error || 'Неизвестная ошибка'));
            statusText.textContent = 'Ошибка';
        }
    } catch (error) {
        hideTyping();
        addMessage('assistant', '❌ Ошибка соединения: ' + error.message);
        statusText.textContent = 'Ошибка соединения';
    }

    setLoading(false);
}

function addMessage(role, content, isLimited = false) {
    // Remove welcome message if exists
    const welcome = chatContainer.querySelector('.welcome-message');
    if (welcome) {
        welcome.remove();
    }

    const messageDiv = document.createElement('div');
    messageDiv.className = `message ${role}${isLimited ? ' limited' : ''}`;
    
    const avatar = document.createElement('div');
    avatar.className = 'message-avatar';
    avatar.textContent = role === 'user' ? '👤' : (isLimited ? '🔬' : '🤖');
    
    const contentDiv = document.createElement('div');
    contentDiv.className = 'message-content';
    
    if (isLimited) {
        const label = document.createElement('div');
        label.className = 'message-label';
        label.textContent = '🔬 Ограниченный запрос';
        contentDiv.appendChild(label);
    }
    
    const textDiv = document.createElement('div');
    
    // Рендерим markdown для ассистента, обычный текст для пользователя
    if (role === 'assistant' && typeof marked !== 'undefined') {
        textDiv.innerHTML = marked.parse(content);
    } else {
        textDiv.textContent = content;
    }
    
    contentDiv.appendChild(textDiv);
    
    messageDiv.appendChild(avatar);
    messageDiv.appendChild(contentDiv);
    chatContainer.appendChild(messageDiv);
    
    // Scroll to bottom
    chatContainer.scrollTop = chatContainer.scrollHeight;
}

// Функция для добавления сообщения с эффектом печатания
async function addMessageWithTyping(role, content, isLimited = false) {
    // Remove welcome message if exists
    const welcome = chatContainer.querySelector('.welcome-message');
    if (welcome) {
        welcome.remove();
    }

    const messageDiv = document.createElement('div');
    messageDiv.className = `message ${role}${isLimited ? ' limited' : ''}`;
    
    const avatar = document.createElement('div');
    avatar.className = 'message-avatar';
    avatar.textContent = role === 'user' ? '👤' : (isLimited ? '🔬' : '🤖');
    
    const contentDiv = document.createElement('div');
    contentDiv.className = 'message-content';
    
    if (isLimited) {
        const label = document.createElement('div');
        label.className = 'message-label';
        label.textContent = '🔬 Ограниченный запрос';
        contentDiv.appendChild(label);
    }
    
    const textDiv = document.createElement('div');
    contentDiv.appendChild(textDiv);
    
    messageDiv.appendChild(avatar);
    messageDiv.appendChild(contentDiv);
    chatContainer.appendChild(messageDiv);
    
    // Эффект печатания
    await typeText(textDiv, content);
    
    // Scroll to bottom
    chatContainer.scrollTop = chatContainer.scrollHeight;
}

// Функция для эффекта печатания
async function typeText(element, text) {
    const chars = text.split('');
    let currentText = '';
    const cursor = document.createElement('span');
    cursor.className = 'typing-cursor';
    
    // Скорость печатания (мс на символ)
    const baseDelay = 15;
    
    for (let i = 0; i < chars.length; i++) {
        currentText += chars[i];
        
        // Рендерим markdown на лету
        if (typeof marked !== 'undefined') {
            element.innerHTML = marked.parse(currentText);
        } else {
            element.textContent = currentText;
        }
        
        // Добавляем курсор
        element.appendChild(cursor);
        
        // Прокрутка вниз
        chatContainer.scrollTop = chatContainer.scrollHeight;
        
        // Случайная задержка для более естественного эффекта
        const delay = baseDelay + Math.random() * 10;
        await sleep(delay);
    }
    
    // Убираем курсор после завершения
    cursor.remove();
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

function showTyping() {
    const typingDiv = document.createElement('div');
    typingDiv.className = 'message assistant';
    typingDiv.id = 'typing-indicator';
    
    const avatar = document.createElement('div');
    avatar.className = 'message-avatar';
    avatar.textContent = '🤖';
    
    const typing = document.createElement('div');
    typing.className = 'typing';
    typing.innerHTML = '<span></span><span></span><span></span>';
    
    typingDiv.appendChild(avatar);
    typingDiv.appendChild(typing);
    chatContainer.appendChild(typingDiv);
    
    chatContainer.scrollTop = chatContainer.scrollHeight;
}

function hideTyping() {
    const typing = document.getElementById('typing-indicator');
    if (typing) {
        typing.remove();
    }
}

function setLoading(loading) {
    isLoading = loading;
    sendBtn.disabled = loading;
    messageInput.disabled = loading;
}

async function clearHistory() {
    if (!confirm('Очистить историю чата?')) return;
    
    try {
        const response = await fetch('/api/clear', {
            method: 'POST'
        });
        
        const data = await response.json();
        
        if (data.success) {
            chatContainer.innerHTML = `
                <div class="welcome-message">
                    <div class="welcome-icon">👋</div>
                    <h2>Привет! Я DeepSeek AI</h2>
                    <p>Задайте мне любой вопрос, и я постараюсь помочь!</p>
                </div>
            `;
            statusText.textContent = 'История очищена';
        }
    } catch (error) {
        statusText.textContent = 'Ошибка при очистке';
    }
}

async function changeMode() {
    const mode = parseInt(modeSelect.value);
    
    try {
        statusText.textContent = 'Смена режима...';
        
        const response = await fetch('/api/mode', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ mode })
        });
        
        const data = await response.json();
        
        if (data.success) {
            modeText.textContent = 'Режим: ' + data.modeName;
            statusText.textContent = data.message;
            
            // Clear chat UI
            chatContainer.innerHTML = `
                <div class="welcome-message">
                    <div class="welcome-icon">${mode === 1 ? '🧪' : '🛠️'}</div>
                    <h2>Режим "${data.modeName}" активирован</h2>
                    <p>История очищена. Готов к работе!</p>
                </div>
            `;
        }
    } catch (error) {
        statusText.textContent = 'Ошибка при смене режима';
    }
}

async function loadHistory() {
    try {
        const response = await fetch('/api/history');
        const data = await response.json();
        
        if (data.history && data.history.length > 0) {
            chatContainer.innerHTML = '';
            data.history.forEach(msg => {
                addMessage(msg.role, msg.content);
            });
        }
        
        modeText.textContent = 'Режим: ' + data.modeName;
        modeSelect.value = data.mode;
    } catch (error) {
        console.error('Error loading history:', error);
    }
}

async function loadMode() {
    try {
        const response = await fetch('/api/mode');
        const data = await response.json();
        
        modeText.textContent = 'Режим: ' + data.modeName;
        modeSelect.value = data.mode;
    } catch (error) {
        console.error('Error loading mode:', error);
    }
}

async function loadSettings() {
    try {
        const response = await fetch('/api/settings');
        const data = await response.json();
        
        if (data.success) {
            const settings = data.settings;
            document.getElementById('maxTokensInput').value = settings.maxTokens;
            document.getElementById('maxTokensEnabled').checked = settings.maxTokensEnabled;
            document.getElementById('stopSequencesInput').value = settings.stopSequences.join(', ');
            document.getElementById('stopSequencesEnabled').checked = settings.stopSequencesEnabled;
            document.getElementById('temperatureInput').value = settings.temperature;
            document.getElementById('temperatureValue').textContent = settings.temperature;
            document.getElementById('temperatureEnabled').checked = settings.temperatureEnabled;
            document.getElementById('systemModeInfo').textContent = settings.modeDescription;
            document.getElementById('systemPromptInput').value = settings.systemPrompt;
        }
    } catch (error) {
        console.error('Error loading settings:', error);
    }
}

async function loadSystemInfo() {
    try {
        // Load system prompt
        const systemResponse = await fetch('/api/system');
        const systemData = await systemResponse.json();
        
        if (systemData.success) {
            document.getElementById('systemPromptInput').value = systemData.systemPrompt;
            document.getElementById('systemModeInfo').textContent = systemData.modeDescription;
        }
        
        // Load system info
        const infoResponse = await fetch('/api/info');
        const infoData = await infoResponse.json();
        
        if (infoData.success) {
            const info = infoData.info;
            document.getElementById('infoJava').textContent = info.javaVersion;
            document.getElementById('infoOs').textContent = info.osName + ' ' + info.osVersion;
            document.getElementById('infoDir').textContent = info.userDir;
            document.getElementById('infoEncoding').textContent = info.fileEncoding;
            document.getElementById('infoUser').textContent = info.userName;
        }
    } catch (error) {
        console.error('Error loading system info:', error);
    }
}

async function saveMaxTokens() {
    const value = parseInt(document.getElementById('maxTokensInput').value);
    
    if (isNaN(value) || value < 1) {
        alert('Введите корректное число токенов');
        return;
    }
    
    try {
        const response = await fetch('/api/settings', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ param: 'max_tokens', value: value })
        });
        
        const data = await response.json();
        
        if (data.success) {
            statusText.textContent = data.message;
            alert('✅ ' + data.message);
        } else {
            alert('Ошибка: ' + data.error);
        }
    } catch (error) {
        alert('Ошибка соединения: ' + error.message);
    }
}

async function saveStopSequences() {
    const value = document.getElementById('stopSequencesInput').value;
    
    try {
        const response = await fetch('/api/settings', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ param: 'stop', value: value })
        });
        
        const data = await response.json();
        
        if (data.success) {
            statusText.textContent = data.message;
            alert('✅ ' + data.message);
        } else {
            alert('Ошибка: ' + data.error);
        }
    } catch (error) {
        alert('Ошибка соединения: ' + error.message);
    }
}

async function saveTemperature() {
    const value = parseFloat(document.getElementById('temperatureInput').value);
    
    if (isNaN(value) || value < 0 || value > 2) {
        alert('Temperature должна быть от 0 до 2');
        return;
    }
    
    try {
        const response = await fetch('/api/settings', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ param: 'temperature', value: value })
        });
        
        const data = await response.json();
        
        if (data.success) {
            statusText.textContent = data.message;
            alert('✅ ' + data.message);
        } else {
            alert('Ошибка: ' + data.error);
        }
    } catch (error) {
        alert('Ошибка соединения: ' + error.message);
    }
}

async function toggleMaxTokens() {
    const enabled = document.getElementById('maxTokensEnabled').checked;
    
    try {
        const response = await fetch('/api/settings', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ param: 'max_tokens_enabled', value: enabled })
        });
        
        const data = await response.json();
        
        if (data.success) {
            statusText.textContent = data.message;
        } else {
            alert('Ошибка: ' + data.error);
            document.getElementById('maxTokensEnabled').checked = !enabled;
        }
    } catch (error) {
        alert('Ошибка соединения: ' + error.message);
        document.getElementById('maxTokensEnabled').checked = !enabled;
    }
}

async function toggleStopSequences() {
    const enabled = document.getElementById('stopSequencesEnabled').checked;
    
    try {
        const response = await fetch('/api/settings', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ param: 'stop_enabled', value: enabled })
        });
        
        const data = await response.json();
        
        if (data.success) {
            statusText.textContent = data.message;
        } else {
            alert('Ошибка: ' + data.error);
            document.getElementById('stopSequencesEnabled').checked = !enabled;
        }
    } catch (error) {
        alert('Ошибка соединения: ' + error.message);
        document.getElementById('stopSequencesEnabled').checked = !enabled;
    }
}

async function toggleTemperature() {
    const enabled = document.getElementById('temperatureEnabled').checked;
    
    try {
        const response = await fetch('/api/settings', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ param: 'temperature_enabled', value: enabled })
        });
        
        const data = await response.json();
        
        if (data.success) {
            statusText.textContent = data.message;
        } else {
            alert('Ошибка: ' + data.error);
            document.getElementById('temperatureEnabled').checked = !enabled;
        }
    } catch (error) {
        alert('Ошибка соединения: ' + error.message);
        document.getElementById('temperatureEnabled').checked = !enabled;
    }
}

async function saveSystemPrompt() {
    const value = document.getElementById('systemPromptInput').value.trim();
    
    if (!value) {
        alert('Системный промпт не может быть пустым');
        return;
    }
    
    try {
        const response = await fetch('/api/settings', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ param: 'system_prompt', value: value })
        });
        
        const data = await response.json();
        
        if (data.success) {
            statusText.textContent = data.message;
            alert('✅ ' + data.message + '. История очищена.');
            // Очищаем чат
            chatContainer.innerHTML = `
                <div class="welcome-message">
                    <div class="welcome-icon">🎭</div>
                    <h2>Системный промпт обновлён</h2>
                    <p>История очищена. Готов к работе!</p>
                </div>
            `;
        } else {
            alert('Ошибка: ' + data.error);
        }
    } catch (error) {
        alert('Ошибка соединения: ' + error.message);
    }
}

async function resetSystemPrompt() {
    const mode = parseInt(modeSelect.value);
    const defaultPrompt = mode === 1 
        ? 'Ты senior тестировщик из Google с 10+ годами опыта. Объясняй концепции тестирования простыми словами, как будто объясняешь джуниору на первом дне работы. Используй практические примеры из реальной разработки. Отвечай кратко и структурированно.'
        : 'Ты полезный помощник';
    
    document.getElementById('systemPromptInput').value = defaultPrompt;
}
""";
}
