package com.example.deepseek.config;

/**
 * Конфигурация приложения.
 * Управляет настройками, которые могут быть переопределены через переменные окружения.
 */
public class AppConfig {

    private static final String ENV_TEST_LIMIT = "TEST_MODE_CONTEXT_LIMIT";
    private static final String ENV_RERANKER_URL = "RERANKER_SERVICE_URL";
    private static final String ENV_LOCAL_LLM_URL = "LOCAL_LLM_URL";
    private static final String ENV_LOCAL_LLM_API_KEY = "LOCAL_LLM_API_KEY";
    private static final String ENV_LOCAL_LLM_MODEL = "LOCAL_LLM_MODEL";
    
    public static final int DEFAULT_CONTEXT_LIMIT = 128000;
    public static final int DEFAULT_MAX_OUTPUT = 32000;
    public static final int MAX_MAX_OUTPUT = 64000;
    public static final String DEFAULT_RERANKER_URL = "http://localhost:8000";
    public static final String DEFAULT_LOCAL_LLM_URL = "http://localhost:8010";

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

    public static String getLocalLlmUrl() {
        String url = System.getenv(ENV_LOCAL_LLM_URL);
        return (url != null && !url.isBlank()) ? url : DEFAULT_LOCAL_LLM_URL;
    }

    public static String getLocalLlmApiKey() {
        return System.getenv(ENV_LOCAL_LLM_API_KEY);
    }

    public static String getLocalLlmModel() {
        return System.getenv(ENV_LOCAL_LLM_MODEL);
    }
}
