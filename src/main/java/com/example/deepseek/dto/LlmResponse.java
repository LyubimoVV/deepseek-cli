package com.example.deepseek.dto;

import com.example.deepseek.db.GlobalSummaryDto;
import com.example.deepseek.db.MessageDto;

public record LlmResponse(
    String content,
    TokenUsage tokenUsage
) {}
