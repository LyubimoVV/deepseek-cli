package com.example.deepseek.client;

/**
 * Базовое исключение для ошибок AI клиентов.
 * Содержит статус код HTTP для API ошибок.
 */
public class AiException extends RuntimeException {

    // Стандартные HTTP статус коды
    public static final int BAD_REQUEST = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int TOO_MANY_REQUESTS = 429;
    public static final int INTERNAL_SERVER_ERROR = 500;
    public static final int SERVICE_UNAVAILABLE = 503;

    // Статус коды для специфичных ошибок AI
    public static final int API_KEY_INVALID = 1001;
    public static final int NETWORK_ERROR = 1002;
    public static final int TIMEOUT = 1003;
    public static final int RATE_LIMIT_EXCEEDED = 1004;
    public static final int INVALID_RESPONSE = 1005;

    private final int statusCode;

    /**
     * Создает исключение без статус кода.
     */
    public AiException(String message) {
        super(message);
        this.statusCode = 0;
    }

    /**
     * Создает исключение без статус кода с причиной.
     */
    public AiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    /**
     * Создает исключение со статус кодом.
     */
    public AiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    /**
     * Создает исключение со статус кодом и причиной.
     */
    public AiException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * Возвращает статус код ошибки.
     * Если статус код не установлен, возвращает 0.
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Проверяет, установлен ли статус код.
     */
    public boolean hasStatusCode() {
        return statusCode > 0;
    }

    /**
     * Проверяет, является ли ошибка клиентской (4xx).
     */
    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }

    /**
     * Проверяет, является ли ошибка серверной (5xx).
     */
    public boolean isServerError() {
        return statusCode >= 500 && statusCode < 600;
    }

    /**
     * Проверяет, является ли ошибка связанной с сетью.
     */
    public boolean isNetworkError() {
        return statusCode == NETWORK_ERROR;
    }

    /**
     * Проверяет, является ли ошибка связанной с таймаутом.
     */
    public boolean isTimeoutError() {
        return statusCode == TIMEOUT;
    }

    /**
     * Проверяет, является ли ошибка связанной с превышением лимита запросов.
     */
    public boolean isRateLimitError() {
        return statusCode == RATE_LIMIT_EXCEEDED || statusCode == TOO_MANY_REQUESTS;
    }

    /**
     * Проверяет, является ли ошибка связанной с неверным API ключом.
     */
    public boolean isApiKeyError() {
        return statusCode == UNAUTHORIZED || statusCode == FORBIDDEN || statusCode == API_KEY_INVALID;
    }

    /**
     * Фабричный метод для создания исключения сетевой ошибки.
     */
    public static AiException networkError(String message, Throwable cause) {
        return new AiException(NETWORK_ERROR, message, cause);
    }

    /**
     * Фабричный метод для создания исключения таймаута.
     */
    public static AiException timeout(String message) {
        return new AiException(TIMEOUT, message);
    }

    /**
     * Фабричный метод для создания исключения превышения лимита запросов.
     */
    public static AiException rateLimitExceeded(String message) {
        return new AiException(RATE_LIMIT_EXCEEDED, message);
    }

    /**
     * Фабричный метод для создания исключения неверного API ключа.
     */
    public static AiException apiKeyInvalid(String message) {
        return new AiException(API_KEY_INVALID, message);
    }

    /**
     * Фабричный метод для создания исключения неверного ответа API.
     */
    public static AiException invalidResponse(String message) {
        return new AiException(INVALID_RESPONSE, message);
    }
}
