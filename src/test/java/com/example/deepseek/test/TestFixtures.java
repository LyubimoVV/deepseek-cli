package com.example.deepseek.test;

import com.example.deepseek.context.ContextStrategy;
import com.example.deepseek.db.GlobalSummaryDto;
import com.example.deepseek.db.MessageDto;
import com.example.deepseek.db.SessionDto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class TestFixtures {

    public static List<MessageDto> createTestMessages(int count) {
        List<MessageDto> messages = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            messages.add(new MessageDto(
                i, 1L, i % 2 == 0 ? "user" : "assistant",
                "Message " + i, 0, 0, 0, 0, 0, 0.0, LocalDateTime.now()
            ));
        }
        return messages;
    }

    public static List<MessageDto> createTestMessages(long sessionId, int count) {
        List<MessageDto> messages = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            messages.add(new MessageDto(
                i, sessionId, i % 2 == 0 ? "user" : "assistant",
                "Message " + i, 0, 0, 0, 0, 0, 0.0, LocalDateTime.now()
            ));
        }
        return messages;
    }

    public static SessionDto createTestSession(long id, String title) {
        return new SessionDto(
            id, title, "deepseek-chat", "You are helpful", 2,
            0, 0.0, 0, LocalDateTime.now(), LocalDateTime.now(),
            0, 3, 10, ContextStrategy.NONE, 10, 10, 1L
        );
    }

    public static SessionDto createTestSession(long id, String title, ContextStrategy strategy) {
        return new SessionDto(
            id, title, "deepseek-chat", "You are helpful", 2,
            0, 0.0, 0, LocalDateTime.now(), LocalDateTime.now(),
            0, 3, 10, strategy, 10, 10, 1L
        );
    }

    public static SessionDto createTestSessionWithWindowSize(long id, String title, int windowSize) {
        return new SessionDto(
            id, title, "deepseek-chat", "You are helpful", 2,
            0, 0.0, 0, LocalDateTime.now(), LocalDateTime.now(),
            0, 3, 10, ContextStrategy.SLIDING_WINDOW, 10, windowSize, 1L
        );
    }

    public static GlobalSummaryDto createTestSummary(long sessionId, String content) {
        return new GlobalSummaryDto(
            sessionId, content, 1, 100L,
            LocalDateTime.now(), 100, 200, 300, 0.01
        );
    }

    public static GlobalSummaryDto createTestSummary(long sessionId, String content, long lastMessageId) {
        return new GlobalSummaryDto(
            sessionId, content, 1, lastMessageId,
            LocalDateTime.now(), 100, 200, 300, 0.01
        );
    }
}
