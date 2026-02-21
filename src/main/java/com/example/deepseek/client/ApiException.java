package com.example.deepseek.client;

/**
 * Исключение для ошибок API, связанных с HTTP запросами.
 * Содержит дополнительную информацию: статус код, тело ответа, URL.
 */
public class ApiException extends AiException {

    private final String responseBody;
    private final String url;
    private final String requestBody;

    /**
     * Создает исключение API с статус кодом и сообщением.
     */
    public ApiException(int statusCode, String message) {
        super(statusCode, message);
        this.responseBody = null;
        this.url = null;
        this.requestBody = null;
    }

    /**
     * Создает исключение API с статус кодом, сообщением и телом ответа.
     */
    public ApiException(int statusCode, String message, String responseBody) {
        super(statusCode, message);
        this.responseBody = responseBody;
        this.url = null;
        this.requestBody = null;
    }

    /**
     * Создает исключение API с полной информацией.
     */
    public ApiException(int statusCode, String message, String responseBody, String url, String requestBody) {
        super(statusCode, message);
        this.responseBody = responseBody;
        this.url = url;
        this.requestBody = requestBody;
    }

    /**
     * Создает исключение API с полной информацией и причиной.
     */
    public ApiException(int statusCode, String message, String responseBody, String url, String requestBody, Throwable cause) {
        super(statusCode, message, cause);
        this.responseBody = responseBody;
        this.url = url;
        this.requestBody = requestBody;
    }

    /**
     * Возвращает тело ответа от API.
     */
    public String getResponseBody() {
        return responseBody;
    }

    /**
     * Возвращает URL запроса.
     */
    public String getUrl() {
        return url;
    }

    /**
     * Возвращает тело запроса.
     */
    public String getRequestBody() {
        return requestBody;
    }

    /**
     * Проверяет, есть ли тело ответа.
     */
    public boolean hasResponseBody() {
        return responseBody != null && !responseBody.isBlank();
    }

    /**
     * Проверяет, есть ли тело запроса.
     */
    public boolean hasRequestBody() {
        return requestBody != null && !requestBody.isBlank();
    }

    /**
     * Проверяет, есть ли URL.
     */
    public boolean hasUrl() {
        return url != null && !url.isBlank();
    }

    /**
     * Фабричный метод для создания исключения неверного API ключа.
     */
    public static ApiException unauthorized(String message, String responseBody, String url) {
        return new ApiException(UNAUTHORIZED, message, responseBody, url, null);
    }

    /**
     * Фабричный метод для создания исключения запрещенного доступа.
     */
    public static ApiException forbidden(String message, String responseBody, String url) {
        return new ApiException(FORBIDDEN, message, responseBody, url, null);
    }

    /**
     * Фабричный метод для создания исключения превышения лимита запросов.
     */
    public static ApiException rateLimitExceeded(String message, String responseBody, String url) {
        return new ApiException(TOO_MANY_REQUESTS, message, responseBody, url, null);
    }

    /**
     * Фабричный метод для создания исключения серверной ошибки.
     */
    public static ApiException serverError(String message, String responseBody, String url) {
        return new ApiException(INTERNAL_SERVER_ERROR, message, responseBody, url, null);
    }

    /**
     * Фабричный метод для создания исключения неверного запроса.
     */
    public static ApiException badRequest(String message, String responseBody, String url, String requestBody) {
        return new ApiException(BAD_REQUEST, message, responseBody, url, requestBody);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString());

        if (hasUrl()) {
            sb.append("\nURL: ").append(url);
        }

        if (hasStatusCode()) {
            sb.append("\nHTTP Status: ").append(getStatusCode());
        }

        if (hasResponseBody()) {
            sb.append("\nResponse: ").append(truncate(responseBody, 500));
        }

        if (hasRequestBody()) {
            sb.append("\nRequest: ").append(truncate(requestBody, 500));
        }

        return sb.toString();
    }

    /**
     * Обрезает строку до указанной длины, добавляя многоточие если нужно.
     */
    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "... [truncated]";
    }
}
