package com.example.deepseek.context.strategies;

import com.example.deepseek.context.ContextStrategy;
import com.example.deepseek.db.BranchDto;
import com.example.deepseek.db.BranchRepository;
import com.example.deepseek.db.MessageDto;
import com.example.deepseek.db.MessageRepository;
import com.example.deepseek.dto.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BranchingContextStrategyHandlerTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private BranchRepository branchRepository;

    private BranchingContextStrategyHandler handler;

    private static final long TEST_SESSION_ID = 1L;
    private static final long MAIN_BRANCH_ID = 1L;
    private static final long TEST_BRANCH_ID = 2L;
    private static final long CHECKPOINT_MESSAGE_ID = 3L;

    @BeforeEach
    void setUp() {
        handler = new BranchingContextStrategyHandler(messageRepository, branchRepository);
    }

    @Test
    void branching_contextForMainBranch_returnsAllMessages() throws SQLException {
        var mainBranch = new BranchDto(MAIN_BRANCH_ID, TEST_SESSION_ID, "main", null, LocalDateTime.now());
        List<MessageDto> mainMessages = List.of(
            new MessageDto(1L, TEST_SESSION_ID, "user", "Hello", 0, 0, 0, 0, 0, 0.0, LocalDateTime.now()),
            new MessageDto(2L, TEST_SESSION_ID, "assistant", "Hi there!", 0, 0, 0, 0, 0, 0.0, LocalDateTime.now())
        );

        when(branchRepository.getActiveBranch(TEST_SESSION_ID)).thenReturn(MAIN_BRANCH_ID);
        when(branchRepository.getBranchById(MAIN_BRANCH_ID)).thenReturn(Optional.of(mainBranch));
        when(messageRepository.getMessagesByBranch(TEST_SESSION_ID, MAIN_BRANCH_ID)).thenReturn(mainMessages);

        List<Message> context = handler.getContext(TEST_SESSION_ID, "System");

        assertThat(context).hasSize(3);
        assertThat(context.get(0).role()).isEqualTo("system");
        assertThat(context.get(0).content()).isEqualTo("System");
        assertThat(context.get(1).role()).isEqualTo("user");
        assertThat(context.get(1).content()).isEqualTo("Hello");
        assertThat(context.get(2).role()).isEqualTo("assistant");
        assertThat(context.get(2).content()).isEqualTo("Hi there!");
    }

    @Test
    void branching_contextWithCheckpoint_inheritsUpToCheckpoint() throws SQLException {
        var testBranch = new BranchDto(TEST_BRANCH_ID, TEST_SESSION_ID, "test", CHECKPOINT_MESSAGE_ID, LocalDateTime.now());
        List<MessageDto> mainMessagesBeforeCheckpoint = List.of(
            new MessageDto(1L, TEST_SESSION_ID, "user", "Hello", 0, 0, 0, 0, 0, 0.0, LocalDateTime.now()),
            new MessageDto(2L, TEST_SESSION_ID, "assistant", "Hi!", 0, 0, 0, 0, 0, 0.0, LocalDateTime.now()),
            new MessageDto(CHECKPOINT_MESSAGE_ID, TEST_SESSION_ID, "user", "Check", 0, 0, 0, 0, 0, 0.0, LocalDateTime.now())
        );
        List<MessageDto> branchMessages = List.of(
            new MessageDto(4L, TEST_SESSION_ID, "assistant", "Branch response", 0, 0, 0, 0, 0, 0.0, LocalDateTime.now())
        );

        when(branchRepository.getActiveBranch(TEST_SESSION_ID)).thenReturn(TEST_BRANCH_ID);
        when(branchRepository.getBranchById(TEST_BRANCH_ID)).thenReturn(Optional.of(testBranch));
        when(branchRepository.getMainBranchId(TEST_SESSION_ID)).thenReturn(MAIN_BRANCH_ID);
        when(messageRepository.getMessagesBeforeCheckpoint(TEST_SESSION_ID, MAIN_BRANCH_ID, CHECKPOINT_MESSAGE_ID))
            .thenReturn(mainMessagesBeforeCheckpoint);
        when(messageRepository.getMessagesByBranch(TEST_SESSION_ID, TEST_BRANCH_ID)).thenReturn(branchMessages);

        List<Message> context = handler.getContext(TEST_SESSION_ID, "System");

        assertThat(context).hasSize(5);
        assertThat(context.get(0).role()).isEqualTo("system");
        assertThat(context.get(1).role()).isEqualTo("user");
        assertThat(context.get(1).content()).isEqualTo("Hello");
        assertThat(context.get(4).role()).isEqualTo("assistant");
        assertThat(context.get(4).content()).isEqualTo("Branch response");
    }

    @Test
    void branching_scheduleAfterMessageSave_doesNothing() {
        handler.scheduleAfterMessageSave(TEST_SESSION_ID, 10);

        verifyNoInteractions(messageRepository, branchRepository);
    }

    @Test
    void branching_validateParameters_doesNothing() {
        handler.validateParameters();

        verifyNoInteractions(messageRepository);
        verifyNoInteractions(branchRepository);
    }

    @Test
    void branching_contextWithNullCheckpoint_loadsOnlyBranchMessages() throws SQLException {
        var isolatedBranch = new BranchDto(TEST_BRANCH_ID, TEST_SESSION_ID, "isolated", null, LocalDateTime.now());
        List<MessageDto> branchMessages = List.of(
            new MessageDto(4L, TEST_SESSION_ID, "user", "Isolated message", 0, 0, 0, 0, 0, 0.0, LocalDateTime.now())
        );

        when(branchRepository.getActiveBranch(TEST_SESSION_ID)).thenReturn(TEST_BRANCH_ID);
        when(branchRepository.getBranchById(TEST_BRANCH_ID)).thenReturn(Optional.of(isolatedBranch));
        when(messageRepository.getMessagesByBranch(TEST_SESSION_ID, TEST_BRANCH_ID)).thenReturn(branchMessages);

        List<Message> context = handler.getContext(TEST_SESSION_ID, "System");

        assertThat(context).hasSize(2);
        assertThat(context.get(0).role()).isEqualTo("system");
        assertThat(context.get(1).role()).isEqualTo("user");
        assertThat(context.get(1).content()).isEqualTo("Isolated message");

        verify(messageRepository, never()).getMessagesBeforeCheckpoint(anyLong(), anyLong(), anyLong());
    }
}

