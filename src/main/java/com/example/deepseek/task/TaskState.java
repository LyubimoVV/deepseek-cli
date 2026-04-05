package com.example.deepseek.task;

public enum TaskState {
    PLANNING("Сбор требований"),
    EXECUTION("Выполнение"),
    VALIDATION("Проверка"),
    DONE("Завершено"),
    PAUSED("Пауза");

    private final String displayName;

    TaskState(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
