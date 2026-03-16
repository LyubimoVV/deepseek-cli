package com.example.deepseek.app.controllers;

import java.time.LocalDateTime;

public class ChatMessage {
    public String role;
    public String content;
    public Integer inputTokens;
    public Integer outputTokens;
    public Integer latency;
    public Double cost;
    public Long id;
    public Boolean isTaskNote;
    public Long taskId;
    public String taskState;
    public LocalDateTime createdAt;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public ChatMessage(String role, String content, int inputTokens, int outputTokens, int latency, double cost) {
        this.role = role;
        this.content = content;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.latency = latency;
        this.cost = cost;
    }

    public ChatMessage(String role, String content, int inputTokens, int outputTokens, int latency, double cost, Long id) {
        this.role = role;
        this.content = content;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.latency = latency;
        this.cost = cost;
        this.id = id;
    }

    public ChatMessage(String role, String content, int inputTokens, int outputTokens, int latency, double cost, Long id,
                       Boolean isTaskNote, Long taskId, String taskState) {
        this.role = role;
        this.content = content;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.latency = latency;
        this.cost = cost;
        this.id = id;
        this.isTaskNote = isTaskNote;
        this.taskId = taskId;
        this.taskState = taskState;
    }

    public ChatMessage(String role, String content, int inputTokens, int outputTokens, int latency, double cost, Long id,
                       Boolean isTaskNote, Long taskId, String taskState, LocalDateTime createdAt) {
        this.role = role;
        this.content = content;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.latency = latency;
        this.cost = cost;
        this.id = id;
        this.isTaskNote = isTaskNote;
        this.taskId = taskId;
        this.taskState = taskState;
        this.createdAt = createdAt;
    }

    public String getRole() { return role; }
    public String getContent() { return content; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public Integer getLatency() { return latency; }
    public Double getCost() { return cost; }
    public Long getId() { return id; }
    public Boolean getIsTaskNote() { return isTaskNote; }
    public Long getTaskId() { return taskId; }
    public String getTaskState() { return taskState; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
