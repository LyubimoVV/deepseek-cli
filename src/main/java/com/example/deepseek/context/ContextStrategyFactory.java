package com.example.deepseek.context;

import com.example.deepseek.context.strategies.CompressionContextStrategyHandler;
import com.example.deepseek.context.strategies.NoneContextStrategyHandler;
import com.example.deepseek.context.strategies.SlidingWindowContextStrategyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContextStrategyFactory {

    private static final Logger log = LoggerFactory.getLogger(ContextStrategyFactory.class);

    private final NoneContextStrategyHandler noneHandler;
    private final CompressionContextStrategyHandler compressionHandler;
    private final SlidingWindowContextStrategyHandler slidingWindowHandler;

    public ContextStrategyFactory(
        NoneContextStrategyHandler noneHandler,
        CompressionContextStrategyHandler compressionHandler,
        SlidingWindowContextStrategyHandler slidingWindowHandler
    ) {
        this.noneHandler = noneHandler;
        this.compressionHandler = compressionHandler;
        this.slidingWindowHandler = slidingWindowHandler;
        
        log.info("ContextStrategyFactory initialized with handlers: NONE, COMPRESSION, SLIDING_WINDOW");
    }

    public ContextStrategyHandler getHandler(ContextStrategy strategy) {
        ContextStrategyHandler handler = switch (strategy) {
            case NONE -> noneHandler;
            case COMPRESSION -> compressionHandler;
            case SLIDING_WINDOW -> slidingWindowHandler;
        };
        
        log.debug("Returning handler for strategy: {}", strategy);
        return handler;
    }
}
