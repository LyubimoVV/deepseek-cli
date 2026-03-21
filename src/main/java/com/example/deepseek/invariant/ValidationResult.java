package com.example.deepseek.invariant;

import java.util.List;

public sealed interface ValidationResult permits ValidationResult.Pass, ValidationResult.Fail, ValidationResult.UserRequestViolation {
    
    record Pass(String response) implements ValidationResult {}
    
    record Fail(List<String> violations) implements ValidationResult {
        public String formatViolations() {
            return "⚠ Нарушение инварианта:\n" + String.join("\n", violations);
        }
    }
    
    record UserRequestViolation(
        List<String> requestedTech,
        List<String> allowedTech,
        String message
    ) implements ValidationResult {
        public String formatMessage() {
            return "⚠ Ваш запрос нарушает ограничения проекта.\n\n" +
                   "Запрошено: " + String.join(", ", requestedTech) + "\n" +
                   "Разрешено: " + String.join(", ", allowedTech) + "\n\n" +
                   "Пожалуйста, скорректируйте запрос в рамках разрешённого стека.";
        }
    }
}
