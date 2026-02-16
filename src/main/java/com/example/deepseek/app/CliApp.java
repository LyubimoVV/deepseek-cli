package com.example.deepseek.app;

import com.example.deepseek.client.DeepSeekClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Интерактивный CLI клиент для DeepSeek API.
 */
public class CliApp {
    
    private static final String EXIT_COMMAND = "/exit";
    private static final String CLEAR_COMMAND = "/clear";
    private static final String HELP_COMMAND = "/help";
    
    public static void main(String[] args) {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Error: DEEPSEEK_API_KEY environment variable is not set");
            System.err.println("Please set it using:");
            System.err.println("  Windows: set DEEPSEEK_API_KEY=your_api_key");
            System.err.println("  Linux/macOS: export DEEPSEEK_API_KEY=your_api_key");
            System.exit(1);
        }
        
        DeepSeekClient client;
        try {
            client = new DeepSeekClient(apiKey);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
            return;
        }
        
        System.out.println("DeepSeek CLI Client");
        System.out.println("Type your message and press Enter.");
        System.out.println("Commands:");
        System.out.println("  " + EXIT_COMMAND + "  - Exit the application");
        System.out.println("  " + CLEAR_COMMAND + " - Clear conversation history");
        System.out.println("  " + HELP_COMMAND + "   - Show this help message");
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
                    System.out.println("Goodbye!");
                    break;
                }
                
                if (CLEAR_COMMAND.equalsIgnoreCase(trimmedInput)) {
                    client.clearHistory();
                    System.out.println("Conversation history cleared.");
                    continue;
                }
                
                if (HELP_COMMAND.equalsIgnoreCase(trimmedInput)) {
                    System.out.println("Commands:");
                    System.out.println("  " + EXIT_COMMAND + "  - Exit the application");
                    System.out.println("  " + CLEAR_COMMAND + " - Clear conversation history");
                    System.out.println("  " + HELP_COMMAND + "   - Show this help message");
                    continue;
                }
                
                if (trimmedInput.isEmpty()) {
                    continue;
                }
                
                try {
                    System.out.println("Thinking...");
                    String response = client.chat(trimmedInput);
                    System.out.println();
                    System.out.println(response);
                    System.out.println();
                } catch (DeepSeekClient.ApiException e) {
                    System.err.println("API Error (" + e.getStatusCode() + "): " + e.getMessage());
                } catch (RuntimeException e) {
                    System.err.println("Error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Fatal I/O error: " + e.getMessage());
            System.exit(1);
        }
    }
}
