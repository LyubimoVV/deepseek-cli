package com.example.deepseek.app;

import com.example.deepseek.client.DeepSeekClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Интерактивный CLI клиент для DeepSeek API.
 */
public class CliApp {

    private static final String EXIT_COMMAND = "/exit";
    private static final String CLEAR_COMMAND = "/clear";
    private static final String HELP_COMMAND = "/help";
    private static final String NORMAL_COMMAND = "/normal";
    private static final String LIMITED_COMMAND = "/limited";
    private static final String SETTINGS_COMMAND = "/settings";
    private static final String SET_COMMAND = "/set";

    public static void main(String[] args) {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Ошибка: переменная окружения DEEPSEEK_API_KEY не установлена");
            System.err.println("Установите её используя:");
            System.err.println("  Windows: set DEEPSEEK_API_KEY=ваш_api_ключ");
            System.err.println("  Linux/macOS: export DEEPSEEK_API_KEY=ваш_api_ключ");
            System.exit(1);
        }

        DeepSeekClient client;
        try {
            client = new DeepSeekClient(apiKey);
        } catch (IllegalArgumentException e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println("DeepSeek CLI - Тестирование форматов ответов");
        System.out.println("Введите сообщение и нажмите Enter.");

        System.out.println("Команды:");
        System.out.println("  " + EXIT_COMMAND + "     - Выход из приложения");
        System.out.println("  " + CLEAR_COMMAND + "    - Очистить историю диалога");
        System.out.println("  " + HELP_COMMAND + "      - Показать справку по командам");
        System.out.println("  " + NORMAL_COMMAND + "    - Отправить обычный запрос без ограничений");
        System.out.println("  " + LIMITED_COMMAND + "   - Отправить ограниченный запрос (200 токенов, \\n\\n стоп)");
        System.out.println("  " + SETTINGS_COMMAND + "  - Показать текущие настройки ограниченного режима");
        System.out.println("  " + SET_COMMAND + "       - Установить параметр ограниченного режима (например, /set max_tokens 150)");
        System.out.println();
        System.out.println("Пример тестового вопроса: '/normal What is unit testing?'");
        System.out.println("Затем сравните с: '/limited What is unit testing?'");
        System.out.println();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                System.out.print("> ");
                String input = reader.readLine();

                if (input == null) {
                    break;
                }

                String trimmedInput = input.trim();

                if (EXIT_COMMAND.equalsIgnoreCase(trimmedInput)) {
                    System.out.println("До свидания!");
                    break;
                }

                if (CLEAR_COMMAND.equalsIgnoreCase(trimmedInput)) {
                    client.clearHistory();
                    System.out.println("История диалога очищена.");
                    continue;
                }

                if (HELP_COMMAND.equalsIgnoreCase(trimmedInput)) {
                    showHelp();
                    continue;
                }

                if (trimmedInput.startsWith(NORMAL_COMMAND)) {
                    handleNormalCommand(client, trimmedInput);
                    continue;
                }

                if (trimmedInput.startsWith(LIMITED_COMMAND)) {
                    handleLimitedCommand(client, trimmedInput);
                    continue;
                }

                if (SETTINGS_COMMAND.equalsIgnoreCase(trimmedInput)) {
                    showSettings(client);
                    continue;
                }

                if (trimmedInput.startsWith(SET_COMMAND)) {
                    handleSetCommand(client, trimmedInput);
                    continue;
                }

                if (trimmedInput.isEmpty()) {
                    continue;
                }

                try {
                    System.out.println("Думаю...");
                    String response = client.chat(trimmedInput);
                    System.out.println();
                    System.out.println(response);
                } catch (DeepSeekClient.ApiException e) {
                    System.err.println("Ошибка API (" + e.getStatusCode() + "): " + e.getMessage());
                } catch (RuntimeException e) {
                    System.err.println("Ошибка: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Критическая ошибка I/O: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void showHelp() {
        System.out.println("Команды:");
        System.out.println("  " + EXIT_COMMAND + "     - Выход из приложения");
        System.out.println("  " + CLEAR_COMMAND + "    - Очистить историю диалога");
        System.out.println("  " + HELP_COMMAND + "      - Показать справку по командам");
        System.out.println("  " + NORMAL_COMMAND + " <question>     - Отправить обычный запрос без ограничений");
        System.out.println("  " + LIMITED_COMMAND + " <question>    - Отправить ограниченный запрос (200 токенов, \\n\\n стоп)");
        System.out.println("  " + SETTINGS_COMMAND + "  - Показать текущие настройки ограниченного режима");
        System.out.println("  " + SET_COMMAND + " <параметр> <значение> - Установить параметр ограниченного режима");
        System.out.println();
        System.out.println("Примеры команды set:");
        System.out.println("  /set max_tokens 150     - Установить максимум токенов: 150");
        System.out.println("  /set stop \"\\n\\n,---\"     - Установить стоп-последовательности (через запятую)");
        System.out.println();
        System.out.println("Пример тестирования:");
        System.out.println("  /normal What is unit testing?");
        System.out.println("  /limited What is unit testing?");
    }

    private static void handleNormalCommand(DeepSeekClient client, String input) {
        String question = input.substring(NORMAL_COMMAND.length()).trim();

        if (question.isEmpty()) {
            System.err.println("Ошибка: Укажите вопрос после " + NORMAL_COMMAND);
            return;
        }

        try {
            System.out.println("[NORMAL] Обычный запрос (без ограничений)...");
            String response = client.chat(question);
            System.out.println();
            System.out.println("[RESULT] Обычный ответ:");
            System.out.println("─".repeat(50));
            System.out.println(response);
            System.out.println("─".repeat(50));
        } catch (DeepSeekClient.ApiException e) {
            System.err.println("Ошибка API (" + e.getStatusCode() + "): " + e.getMessage());
        } catch (RuntimeException e) {
            System.err.println("Ошибка: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Неожиданная ошибка: " + e.getMessage());
        }
    }

    private static void handleLimitedCommand(DeepSeekClient client, String input) {
        String question = input.substring(LIMITED_COMMAND.length()).trim();

        if (question.isEmpty()) {
            System.err.println("Ошибка: Укажите вопрос после " + LIMITED_COMMAND);
            return;
        }

        try {
            System.out.println("[LIMITED] Ограниченный запрос (макс " + client.getMaxTokens() + " токенов, \\n\\n стоп)...");
            String response = client.chatLimited(question);
            System.out.println();
            System.out.println("[RESULT] Ограниченный ответ:");
            System.out.println("─".repeat(50));
            System.out.println(response);
            System.out.println("─".repeat(50));
        } catch (DeepSeekClient.ApiException e) {
            System.err.println("Ошибка API (" + e.getStatusCode() + "): " + e.getMessage());
        } catch (RuntimeException e) {
            System.err.println("Ошибка: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Неожиданная ошибка: " + e.getMessage());
        }
    }

    private static void showSettings(DeepSeekClient client) {
        System.out.println("[SETTINGS] Текущие настройки ограниченного режима:");
        System.out.println("─".repeat(40));
        System.out.println("Макс токенов: " + client.getMaxTokens());
        System.out.println("Стоп-последовательности: " + client.getStopSequences());
        System.out.println("Системное сообщение: " + client.getLimitedSystemMessage());
        System.out.println("─".repeat(40));
    }

    private static void handleSetCommand(DeepSeekClient client, String input) {
        String params = input.substring(SET_COMMAND.length()).trim();
        if (params.isEmpty()) {
            System.err.println("Ошибка: Укажите параметр и значение");
            System.err.println("Примеры:");
            System.err.println("  /set max_tokens 150");
            System.err.println("  /set stop \"###,---\"");
            return;
        }

        String[] parts = params.split("\\s+", 2);
        if (parts.length != 2) {
            System.err.println("Ошибка: Укажите и параметр, и значение");
            return;
        }

        String param = parts[0].toLowerCase();
        String value = parts[1];

        try {
            switch (param) {
                case "max_tokens" -> {
                    int tokens = Integer.parseInt(value);
                    client.setMaxTokens(tokens);
                    System.out.println("[OK] Максимум токенов установлен: " + tokens);
                }
                case "stop" -> {
                    // Remove quotes if present and split by comma
                    String cleanValue = value.replaceAll("^[\"']|[\"']$", "");
                    String[] sequences = cleanValue.split(",");
                    List<String> stopList = new ArrayList<>();
                    for (String seq : sequences) {
                        // Handle escaped newlines
                        String processed = seq.trim().replace("\\n", "\n");
                        if (!processed.isEmpty()) {
                            stopList.add(processed);
                        }
                    }
                    client.setStopSequences(stopList);
                    System.out.println("[OK] Стоп-последовательности установлены: " + stopList);
                }
                case "system_message" -> {
                    String cleanValue = value.replaceAll("^[\"']|[\"']$", "");
                    client.setLimitedSystemMessage(cleanValue);
                    System.out.println("[OK] Системное сообщение обновлено");
                }
                default -> {
                    System.err.println("Ошибка: Неизвестный параметр '" + param + "'");
                    System.err.println("Доступные параметры: max_tokens, stop, system_message");
                }
            }
        } catch (NumberFormatException e) {
            System.err.println("Ошибка: Неверный числовой формат для " + param);
        } catch (IllegalArgumentException e) {
            System.err.println("Ошибка: " + e.getMessage());
        }
    }
}
