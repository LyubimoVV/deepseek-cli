package com.example.deepseek.context;

import com.example.deepseek.context.strategies.BranchingContextStrategyHandler;
import com.example.deepseek.context.strategies.CompressionContextStrategyHandler;
import com.example.deepseek.context.strategies.NoneContextStrategyHandler;
import com.example.deepseek.context.strategies.SlidingWindowContextStrategyHandler;
import com.example.deepseek.context.strategies.StickyFactsContextStrategyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContextStrategyFactory {

    private static final Logger log = LoggerFactory.getLogger(ContextStrategyFactory.class);

    private final NoneContextStrategyHandler noneHandler;
    private final CompressionContextStrategyHandler compressionHandler;
    private final SlidingWindowContextStrategyHandler slidingWindowHandler;
    private final StickyFactsContextStrategyHandler stickyFactsHandler;
    private final BranchingContextStrategyHandler branchingHandler;

    public ContextStrategyFactory(
        NoneContextStrategyHandler noneHandler,
        CompressionContextStrategyHandler compressionHandler,
        SlidingWindowContextStrategyHandler slidingWindowHandler,
        StickyFactsContextStrategyHandler stickyFactsHandler,
        BranchingContextStrategyHandler branchingHandler
    ) {
        this.noneHandler = noneHandler;
        this.compressionHandler = compressionHandler;
        this.slidingWindowHandler = slidingWindowHandler;
        this.stickyFactsHandler = stickyFactsHandler;
        this.branchingHandler = branchingHandler;

        log.info("ContextStrategyFactory initialized with handlers: NONE, COMPRESSION, SLIDING_WINDOW, STICKY_FACTS, BRANCHING");
    }

    public ContextStrategyHandler getHandler(ContextStrategy strategy) {
        ContextStrategyHandler handler = switch (strategy) {
            case NONE -> noneHandler;
            case COMPRESSION -> compressionHandler;
            case SLIDING_WINDOW -> slidingWindowHandler;
            case STICKY_FACTS -> stickyFactsHandler;
            case BRANCHING -> branchingHandler;
        };

        log.debug("Returning handler for strategy: {}", strategy);
        return handler;
    }
}
