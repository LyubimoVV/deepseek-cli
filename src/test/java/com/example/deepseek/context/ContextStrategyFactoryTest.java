package com.example.deepseek.context;

import com.example.deepseek.context.strategies.BranchingContextStrategyHandler;
import com.example.deepseek.context.strategies.CompressionContextStrategyHandler;
import com.example.deepseek.context.strategies.NoneContextStrategyHandler;
import com.example.deepseek.context.strategies.SlidingWindowContextStrategyHandler;
import com.example.deepseek.context.strategies.StickyFactsContextStrategyHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ContextStrategyFactoryTest {

    @Mock
    private NoneContextStrategyHandler noneHandler;

    @Mock
    private CompressionContextStrategyHandler compressionHandler;

    @Mock
    private SlidingWindowContextStrategyHandler slidingWindowHandler;

    @Mock
    private StickyFactsContextStrategyHandler stickyFactsHandler;

    @Mock
    private BranchingContextStrategyHandler branchingHandler;

    private ContextStrategyFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ContextStrategyFactory(noneHandler, compressionHandler, slidingWindowHandler, stickyFactsHandler, branchingHandler);
    }

    @Test
    void getHandler_NONE_returnsNoneHandler() {
        ContextStrategyHandler handler = factory.getHandler(ContextStrategy.NONE);

        assertThat(handler).isSameAs(noneHandler);
    }

    @Test
    void getHandler_COMPRESSION_returnsCompressionHandler() {
        ContextStrategyHandler handler = factory.getHandler(ContextStrategy.COMPRESSION);

        assertThat(handler).isSameAs(compressionHandler);
    }

    @Test
    void getHandler_SLIDING_WINDOW_returnsSlidingWindowHandler() {
        ContextStrategyHandler handler = factory.getHandler(ContextStrategy.SLIDING_WINDOW);

        assertThat(handler).isSameAs(slidingWindowHandler);
    }

    @Test
    void getHandler_STICKY_FACTS_returnsStickyFactsHandler() {
        ContextStrategyHandler handler = factory.getHandler(ContextStrategy.STICKY_FACTS);

        assertThat(handler).isSameAs(stickyFactsHandler);
    }

    @Test
    void getHandler_BRANCHING_returnsBranchingHandler() {
        ContextStrategyHandler handler = factory.getHandler(ContextStrategy.BRANCHING);

        assertThat(handler).isSameAs(branchingHandler);
    }

    @Test
    void getHandler_allStrategies_returnCorrectHandlers() {
        assertThat(factory.getHandler(ContextStrategy.NONE)).isSameAs(noneHandler);
        assertThat(factory.getHandler(ContextStrategy.COMPRESSION)).isSameAs(compressionHandler);
        assertThat(factory.getHandler(ContextStrategy.SLIDING_WINDOW)).isSameAs(slidingWindowHandler);
        assertThat(factory.getHandler(ContextStrategy.STICKY_FACTS)).isSameAs(stickyFactsHandler);
        assertThat(factory.getHandler(ContextStrategy.BRANCHING)).isSameAs(branchingHandler);
    }
}
