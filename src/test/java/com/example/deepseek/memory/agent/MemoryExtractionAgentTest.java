package com.example.deepseek.memory.agent;

import com.example.deepseek.client.ClientManager;
import com.example.deepseek.memory.MemoryLayer;
import com.example.deepseek.memory.MemoryScope;
import com.example.deepseek.memory.dto.MemorySuggestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryExtractionAgentTest {

    @Mock
    private ClientManager clientManager;

    @Mock
    private com.example.deepseek.client.AiClient aiClient;

    private MemoryExtractionAgent agent;

    @BeforeEach
    void setUp() {
        agent = new MemoryExtractionAgent(clientManager);
    }

    @Test
    void analyze_returns_suggestions() {
        var mockResponse = new com.example.deepseek.dto.LlmResponse(
            """
                {
                  "suggestions": [
                    {
                      "key": "preferred_language",
                      "value": "русский",
                      "layer": "LONG_TERM",
                      "category": "preferences",
                      "confidence": 0.9,
                      "explanation": "Пользователь указал предпочтение языка"
                    }
                  ]
                }
                """,
            new com.example.deepseek.dto.TokenUsage(100, 50, 150)
        );

        when(clientManager.getCurrentClient()).thenReturn(aiClient);
        when(aiClient.chatWithMessages(anyList())).thenReturn(mockResponse);

        List<MemorySuggestion> suggestions = agent.analyze("Я предпочитаю код на русском", MemoryScope.ofSession(1L));

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).key()).isEqualTo("preferred_language");
        assertThat(suggestions.get(0).value()).isEqualTo("русский");
        assertThat(suggestions.get(0).layer()).isEqualTo(MemoryLayer.LONG_TERM);
        assertThat(suggestions.get(0).confidence()).isEqualTo(0.9);
    }

    @Test
    void analyze_returns_empty_for_no_facts() {
        var mockResponse = new com.example.deepseek.dto.LlmResponse(
            """
                {
                  "suggestions": []
                }
                """,
            new com.example.deepseek.dto.TokenUsage(100, 50, 150)
        );

        when(clientManager.getCurrentClient()).thenReturn(aiClient);
        when(aiClient.chatWithMessages(anyList())).thenReturn(mockResponse);

        List<MemorySuggestion> suggestions = agent.analyze("Привет, как дела?", MemoryScope.ofSession(1L));

        assertThat(suggestions).isEmpty();
    }
}
