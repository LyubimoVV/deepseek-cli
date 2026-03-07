package com.example.deepseek.agent;

import com.example.deepseek.db.GlobalSummaryDto;
import com.example.deepseek.db.MessageDto;
import com.example.deepseek.dto.LlmResponse;
import com.example.deepseek.dto.Message;
import com.example.deepseek.dto.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SummaryAgentTest {

    @Mock
    private com.example.deepseek.client.ClientManager mockClientManager;

    @Mock
    private com.example.deepseek.db.SessionService mockSessionService;

    @Mock
    private com.example.deepseek.client.AiClient mockAiClient;

    private SummaryAgent summaryAgent;

    @BeforeEach
    void setUp() {
        lenient().when(mockClientManager.hasClient("deepseek-chat")).thenReturn(true);
        lenient().when(mockClientManager.getClient("deepseek-chat")).thenReturn(mockAiClient);
        lenient().when(mockAiClient.getCurrentModel()).thenReturn("deepseek-chat");
        lenient().when(mockAiClient.getProviderName()).thenReturn("DeepSeek");
        lenient().when(mockAiClient.getModelDisplayName()).thenReturn("DeepSeek Chat");

        lenient().when(mockAiClient.chatWithMessages(any(List.class)))
            .thenReturn(new LlmResponse("Test summary", new TokenUsage(10, 5, 15)));

        summaryAgent = new SummaryAgent(mockClientManager, mockSessionService);
    }

    @Test
    void formatMessages_formatsCorrectly() {
        List<MessageDto> messages = List.of(
            createMessageDto("user", "Привет"),
            createMessageDto("assistant", "Hi!"),
            createMessageDto("user", "Time: 14:00")
        );

        String result = summaryAgent.formatMessages(messages);

        assertThat(result)
            .isEqualTo("[user] Привет | [assistant] Hi! | [user] Time: 14:00");
    }

    @Test
    void formatMessages_handlesNullContent() {
        List<MessageDto> messages = List.of(
            createMessageDto("user", null)
        );

        String result = summaryAgent.formatMessages(messages);

        assertThat(result).isEqualTo("[user] ");
    }

    @Test
    void formatMessages_handlesEmptyList() {
        List<MessageDto> messages = List.of();

        String result = summaryAgent.formatMessages(messages);

        assertThat(result).isEmpty();
    }

    @Test
    void formatMessages_handlesMixedContent() {
        List<MessageDto> messages = List.of(
            createMessageDto("user", "Первая строка\nВторая строка"),
            createMessageDto("assistant", "  Trim test  "),
            createMessageDto("user", "")
        );

        String result = summaryAgent.formatMessages(messages);

        assertThat(result)
            .isEqualTo("[user] Первая строка\nВторая строка | [assistant] Trim test | [user] ");
    }

    @Test
    void formatMessages_trimsWhitespace() {
        List<MessageDto> messages = List.of(
            createMessageDto("user", "   spaces around   ")
        );

        String result = summaryAgent.formatMessages(messages);

        assertThat(result).isEqualTo("[user] spaces around");
    }

    @Test
    void generateSummaryFromMessages_includesOldSummary() throws Exception {
        GlobalSummaryDto oldSummary = new GlobalSummaryDto(
            1L,
            "Previous discussion about computers",
            1,
            10L,
            LocalDateTime.now(),
            null,
            null,
            null,
            null
        );

        List<MessageDto> newMessages = List.of(
            createMessageDto("user", "Tell me about GPUs")
        );

        summaryAgent.generateSummaryFromMessages(Optional.of(oldSummary), newMessages);
    }

    @Test
    void generateSummaryFromMessages_worksWithoutOldSummary() throws Exception {
        List<MessageDto> messages = List.of(
            createMessageDto("user", "Hello")
        );

        summaryAgent.generateSummaryFromMessages(Optional.empty(), messages);
    }

    @Test
    void generateSummaryFromMessages_overloadedMethod() throws Exception {
        List<MessageDto> messages = List.of(
            createMessageDto("user", "Test")
        );

        summaryAgent.generateSummaryFromMessages(messages);
    }

    private MessageDto createMessageDto(String role, String content) {
        return new MessageDto(1, 1, role, content, 0, 0, 0, 0, 0, 0.0, null);
    }
}
