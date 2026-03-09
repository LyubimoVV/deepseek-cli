package com.example.deepseek.test;

import com.example.deepseek.db.MessageDto;
import com.example.deepseek.db.SessionRepository;

import java.util.List;

import static org.mockito.Mockito.mock;

public class MockitoHelpers {

    public static SessionRepository mockSessionRepository(int windowSize) {
        SessionRepository repo = mock(SessionRepository.class);
        return repo;
    }

    public static MessageDto mockMessage(String role, String content) {
        return mock(MessageDto.class);
    }

    public static List<MessageDto> mockMessageList(int count) {
        return mock(List.class);
    }
}
