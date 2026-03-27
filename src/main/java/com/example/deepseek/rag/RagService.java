package com.example.deepseek.rag;

import com.example.deepseek.embedding.EmbeddingService;
import com.example.deepseek.embedding.index.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class RagService {
    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final int DEFAULT_TOP_K = 5;
    private static final String DEFAULT_STRATEGY = "BOTH";

    private final EmbeddingService embeddingService;

    public RagService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    public String augmentWithRag(String userQuery) {
        return augmentWithRag(userQuery, DEFAULT_TOP_K, DEFAULT_STRATEGY);
    }

    public String augmentWithRag(String userQuery, String strategy) {
        return augmentWithRag(userQuery, DEFAULT_TOP_K, strategy);
    }

    public String augmentWithRag(String userQuery, int topK, String strategy) {
        log.info("=== RAG START ===");
        log.info("Query: {}", userQuery);
        
        if (embeddingService == null) {
            log.warn("EmbeddingService is null, returning original query");
            log.info("=== RAG END (no service) ===");
            return userQuery;
        }

        try {
            log.info("Searching chunks: topK={}, strategy={}", topK, strategy);
            List<SearchResult> results = embeddingService.search(userQuery, topK, strategy);
            
            if (results.isEmpty()) {
                log.warn("No relevant chunks found for query");
                log.info("=== RAG END (no results) ===");
                return userQuery;
            }

            log.info("Found {} relevant chunks:", results.size());
            for (int i = 0; i < results.size(); i++) {
                SearchResult r = results.get(i);
                log.info("  [{}] score={} source={} section={}", 
                    i+1, String.format("%.4f", r.score()), r.source(), r.section());
                log.debug("      content_preview: {}", 
                    r.content().substring(0, Math.min(100, r.content().length())) + "...");
            }

            StringBuilder context = new StringBuilder();
            context.append("Контекст из базы знаний:\n");
            
            for (int i = 0; i < results.size(); i++) {
                SearchResult r = results.get(i);
                context.append("---\n");
                context.append(r.content()).append("\n");
            }
            context.append("---\n\n");
            context.append("Вопрос: ").append(userQuery);

            String result = context.toString();
            log.info("Augmented prompt: {} chars (original: {})", result.length(), userQuery.length());
            log.debug("Full augmented prompt:\n{}", result);
            log.info("=== RAG END ===");

            return result;
        } catch (Exception e) {
            log.error("Error during RAG augmentation: {}", e.getMessage(), e);
            log.info("=== RAG END (error) ===");
            return userQuery;
        }
    }

    public boolean isAvailable() {
        return embeddingService != null && 
               embeddingService.isOllamaAvailable() && 
               embeddingService.hasOllamaModel();
    }

    public int getChunksCount() {
        if (embeddingService == null) return 0;
        try {
            return embeddingService.getStats().totalVectorCount();
        } catch (Exception e) {
            return 0;
        }
    }

    public EmbeddingService getEmbeddingService() {
        return embeddingService;
    }
}
