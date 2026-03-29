package com.example.deepseek.config;

/**
 * Конфигурация приложения.
 * Управляет настройками, которые могут быть переопределены через переменные окружения.
 */
public class AppConfig {

    private static final String ENV_TEST_LIMIT = "TEST_MODE_CONTEXT_LIMIT";
    private static final String ENV_RERANKER_URL = "RERANKER_SERVICE_URL";
    
    public static final int DEFAULT_CONTEXT_LIMIT = 128000;
    public static final int DEFAULT_MAX_OUTPUT = 32000;
    public static final int MAX_MAX_OUTPUT = 64000;
    public static final String DEFAULT_RERANKER_URL = "http://localhost:8000";

    private AppConfig() {}

    public static Integer getTestContextLimit() {
        String limitEnv = System.getenv(ENV_TEST_LIMIT);
        if (limitEnv != null && !limitEnv.isBlank()) {
            try {
                return Integer.parseInt(limitEnv);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static int getContextLimit() {
        Integer testLimit = getTestContextLimit();
        return testLimit != null ? testLimit : DEFAULT_CONTEXT_LIMIT;
    }

    public static boolean isTestMode() {
        return getTestContextLimit() != null;
    }
    
    public static String getRerankerServiceUrl() {
        String url = System.getenv(ENV_RERANKER_URL);
        return (url != null && !url.isBlank()) ? url : DEFAULT_RERANKER_URL;
    }
}
