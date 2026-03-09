package com.example.deepseek.context;

import com.example.deepseek.context.strategies.CompressionContextStrategyHandler;
import com.example.deepseek.context.strategies.NoneContextStrategyHandler;
import com.example.deepseek.context.strategies.SlidingWindowContextStrategyHandler;
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

    private ContextStrategyFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ContextStrategyFactory(noneHandler, compressionHandler, slidingWindowHandler);
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
    void getHandler_allStrategies_returnCorrectHandlers() {
        assertThat(factory.getHandler(ContextStrategy.NONE)).isSameAs(noneHandler);
        assertThat(factory.getHandler(ContextStrategy.COMPRESSION)).isSameAs(compressionHandler);
        assertThat(factory.getHandler(ContextStrategy.SLIDING_WINDOW)).isSameAs(slidingWindowHandler);
    }
}
