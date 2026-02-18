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
    private static final String LIMITED_COMMAND = "/limited";
    private static final String LIMITED_SETTINGS_COMMAND = "/limited_settings";
    private static final String SET_COMMAND = "/set";
    private static final String MODE_COMMAND = "/mode";
    private static final String SYSTEM_COMMAND = "/system";

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
        System.out.println("  " + LIMITED_COMMAND + "   - Отправить ограниченный запрос (200 токенов, \\n\\n стоп)");
        System.out.println("  " + LIMITED_SETTINGS_COMMAND + "  - Показать текущие настройки ограниченного режима");
        System.out.println("  " + SET_COMMAND + "       - Установить параметр ограниченного режима (например, /set max_tokens 150)");
        System.out.println("  " + MODE_COMMAND + "      - Выбрать режим системного сообщения (1 - Тестировщик, 2 - Обычный помощник)");
        System.out.println("  " + SYSTEM_COMMAND + "    - Показать текущий системный промпт");
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

                if (LIMITED_SETTINGS_COMMAND.equalsIgnoreCase(trimmedInput)) {
                    showLimitedSettings(client);
                    continue;
                }

                if (trimmedInput.startsWith(LIMITED_COMMAND)) {
                    handleLimitedCommand(client, trimmedInput);
                    continue;
                }

                if (trimmedInput.startsWith(SET_COMMAND)) {
                    handleSetCommand(client, trimmedInput);
                    continue;
                }

                if (trimmedInput.startsWith(MODE_COMMAND)) {
                    handleModeCommand(client, trimmedInput);
                    continue;
                }

                if (SYSTEM_COMMAND.equalsIgnoreCase(trimmedInput)) {
                    showSystemPrompt(client);
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
        System.out.println("  " + LIMITED_COMMAND + " <question>    - Отправить ограниченный запрос (200 токенов, \\n\\n стоп)");
        System.out.println("  " + LIMITED_SETTINGS_COMMAND + "  - Показать текущие настройки ограниченного режима");
        System.out.println("  " + SET_COMMAND + " <параметр> <значение> - Установить параметр ограниченного режима");
        System.out.println("  " + MODE_COMMAND + " <режим> - Выбрать режим системного сообщения (1 - Тестировщик, 2 - Обычный помощник)");
        System.out.println("  " + SYSTEM_COMMAND + " - Показать текущий системный промпт");
        System.out.println();
        System.out.println("Примеры команды set:");
        System.out.println("  /set max_tokens 150     - Установить максимум токенов: 150");
        System.out.println("  /set stop \"\\n\\n,---\"     - Установить стоп-последовательности (через запятую)");
        System.out.println();
        System.out.println("Для смены системного сообщения используйте команду /mode");
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

    private static void showLimitedSettings(DeepSeekClient client) {
        System.out.println("[LIMITED SETTINGS] Настройки ограниченного режима:");
        System.out.println("─".repeat(40));
        System.out.println("Макс токенов: " + client.getMaxTokens());
        System.out.println("Стоп-последовательности: " + client.getStopSequences());
        System.out.println("─".repeat(40));
        System.out.println("Текущий режим: " + getModeDescription(client.getCurrentSystemMessage()));
    }

    private static String getModeDescription(String systemMessage) {
        if (systemMessage.contains("тестировщик")) {
            return "Тестировщик (Senior QA Engineer)";
        } else {
            return "Обычный помощник (General Assistant)";
        }
    }

    private static void showSystemPrompt(DeepSeekClient client) {
        System.out.println("[SYSTEM PROMPT] Текущий системный промпт:");
        System.out.println("─".repeat(50));
        System.out.println(client.getCurrentSystemMessage());
        System.out.println("─".repeat(50));
        System.out.println("Текущий режим: " + getModeDescription(client.getCurrentSystemMessage()));
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
                default -> {
                    System.err.println("Ошибка: Неизвестный параметр '" + param + "'");
                    System.err.println("Доступные параметры: max_tokens, stop");
                }
            }
        } catch (NumberFormatException e) {
            System.err.println("Ошибка: Неверный числовой формат для " + param);
        } catch (IllegalArgumentException e) {
            System.err.println("Ошибка: " + e.getMessage());
        }
    }

    private static void handleModeCommand(DeepSeekClient client, String input) {
        String modeStr = input.substring(MODE_COMMAND.length()).trim();

        if (modeStr.isEmpty()) {
            System.err.println("Ошибка: Укажите режим (1 или 2)");
            System.err.println("  1 - Тестировщик (Senior QA Engineer)");
            System.err.println("  2 - Обычный помощник (General Assistant)");
            return;
        }

        try {
            int mode = Integer.parseInt(modeStr);
            client.setSystemMessage(mode);
            String modeName = mode == 1 ? "Тестировщик" : "Обычный помощник";
            System.out.println("[OK] Режим изменен на: " + modeName);
            System.out.println("[INFO] История диалога очищена");
        } catch (NumberFormatException e) {
            System.err.println("Ошибка: Укажите цифру (1 или 2)");
        } catch (IllegalArgumentException e) {
            System.err.println("Ошибка: " + e.getMessage());
        }
    }
}
