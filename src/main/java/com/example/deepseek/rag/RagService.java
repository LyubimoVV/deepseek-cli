package com.example.deepseek.rag;

import com.example.deepseek.app.controllers.AppContext;
import com.example.deepseek.embedding.EmbeddingService;
import com.example.deepseek.embedding.index.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

public class RagService {
    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final int DEFAULT_TOP_K = 5;
    private static final int SEARCH_TOP_K = 20;
    private static final String DEFAULT_STRATEGY = "BOTH";
    
    private static final String RAG_SYSTEM_PROMPT = """

=== ИНСТРУКЦИЯ ПО ФОРМАТИРОВАНИЮ ОТВЕТА ===

1. Дай развёрнутый ответ на вопрос
2. Добавь раздел "Источники:" со списком использованных материалов:
   - [файл] > [раздел/строки]
3. Добавь раздел "Цитаты:" с точными фрагментами из контекста, подтверждающими ответ

Если в контексте НЕТ информации для ответа:
- Напиши ТОЛЬКО: "К сожалению, в базе знаний нет информации по вашему вопросу. Пожалуйста, уточните или переформулируйте запрос."
- НЕ добавляй разделы "Источники:" и "Цитаты:"

Не выдумывай информацию, которой нет в контексте!
""";

    private final EmbeddingService embeddingService;
    private final AppContext appContext;
    private RerankerService rerankerService;

    public RagService(EmbeddingService embeddingService, AppContext appContext) {
        this.embeddingService = embeddingService;
        this.appContext = appContext;
    }

    public void setRerankerService(RerankerService rerankerService) {
        this.rerankerService = rerankerService;
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

        boolean rerankerEnabled = appContext.isRerankerEnabled();
        double rerankerThreshold = appContext.getRerankerThreshold();
        int rerankerTopKBefore = appContext.getRerankerTopKBefore();
        int rerankerTopKAfter = appContext.getRerankerTopKAfter();

        try {
            int searchTopK = rerankerEnabled ? rerankerTopKBefore : topK;
            log.info("Searching chunks: searchTopK={}, strategy={}, rerankerEnabled={}", searchTopK, strategy, rerankerEnabled);
            
            List<SearchResult> results = embeddingService.search(userQuery, searchTopK, strategy);
            
            if (results.isEmpty()) {
                log.warn("No relevant chunks found for query");
                log.info("=== RAG END (no results) ===");
                return userQuery;
            }

            if (rerankerEnabled && rerankerService != null && rerankerService.isAvailable()) {
                log.info("Applying reranker: threshold={}, topKAfter={}", rerankerThreshold, rerankerTopKAfter);
                results = rerankerService.rerank(userQuery, results, rerankerThreshold, rerankerTopKAfter);
                
                if (results.isEmpty()) {
                    log.warn("No results after reranking with threshold {}", rerankerThreshold);
                    log.info("=== RAG END (no results after rerank) ===");
                    return userQuery;
                }
            } else {
                if (results.size() > topK) {
                    results = results.subList(0, topK);
                }
            }

            log.info("Found {} relevant chunks:", results.size());
            for (int i = 0; i < results.size(); i++) {
                SearchResult r = results.get(i);
                String scoreInfo = r.rerankScore() != null ? 
                    "rerankScore=" + String.format("%.4f", r.rerankScore()) :
                    "score=" + String.format("%.4f", r.score());
                log.info("  [{}] {} source={} section={}", 
                    i+1, scoreInfo, r.source(), r.section());
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

    public RagResult augmentWithRagResult(String userQuery, String strategy) {
        log.info("=== RAG START (with result) ===");
        log.info("Query: {}", userQuery);
        
        if (embeddingService == null) {
            log.warn("EmbeddingService is null");
            return new RagResult(userQuery, List.of(), false, 0.0);
        }

        boolean rerankerEnabled = appContext.isRerankerEnabled();
        double rerankerThreshold = appContext.getRerankerThreshold();
        int rerankerTopKBefore = appContext.getRerankerTopKBefore();
        int rerankerTopKAfter = appContext.getRerankerTopKAfter();

        try {
            int searchTopK = rerankerEnabled ? rerankerTopKBefore : DEFAULT_TOP_K;
            log.info("Searching chunks: searchTopK={}, strategy={}, rerankerEnabled={}", searchTopK, strategy, rerankerEnabled);
            
            List<SearchResult> results = embeddingService.search(userQuery, searchTopK, strategy);
            
            if (results.isEmpty()) {
                log.warn("No relevant chunks found for query");
                log.info("=== RAG END (no results) ===");
                return new RagResult(userQuery, List.of(), false, 0.0);
            }

            if (rerankerEnabled && rerankerService != null && rerankerService.isAvailable()) {
                log.info("Applying reranker: threshold={}, topKAfter={}", rerankerThreshold, rerankerTopKAfter);
                results = rerankerService.rerank(userQuery, results, rerankerThreshold, rerankerTopKAfter);
                
                if (results.isEmpty()) {
                    log.warn("No results after reranking with threshold {}", rerankerThreshold);
                    log.info("=== RAG END (no results after rerank) ===");
                    return new RagResult(userQuery, List.of(), false, 0.0);
                }
            } else {
                if (results.size() > DEFAULT_TOP_K) {
                    results = results.subList(0, DEFAULT_TOP_K);
                }
            }

            double maxScore = results.stream()
                .mapToDouble(r -> r.rerankScore() != null ? r.rerankScore() : r.score())
                .max()
                .orElse(0.0);
            
            boolean hasRelevant = maxScore >= rerankerThreshold;
            log.info("Max relevance score: {}, threshold: {}, hasRelevant: {}", maxScore, rerankerThreshold, hasRelevant);

            List<RagResult.SourceInfo> sources = results.stream()
                .map(r -> new RagResult.SourceInfo(
                    r.chunkId(),
                    r.source(),
                    r.section(),
                    r.content(),
                    r.rerankScore() != null ? r.rerankScore() : r.score()
                ))
                .collect(Collectors.toList());

            log.info("Found {} relevant chunks:", results.size());
            for (int i = 0; i < results.size(); i++) {
                SearchResult r = results.get(i);
                String scoreInfo = r.rerankScore() != null ? 
                    "rerankScore=" + String.format("%.4f", r.rerankScore()) :
                    "score=" + String.format("%.4f", r.score());
                String contentPreview = r.content().substring(0, Math.min(80, r.content().length())).replace("\n", " ");
                log.info("  [{}] {} section={} preview='{}'", 
                    i+1, scoreInfo, r.section(), contentPreview);
            }

            StringBuilder context = new StringBuilder();
            context.append("=== КОНТЕКСТ ИЗ БАЗЫ ЗНАНИЙ ===\n\n");
            
            for (int i = 0; i < results.size(); i++) {
                SearchResult r = results.get(i);
                context.append("[Источник ").append(i+1).append(": ")
                    .append(r.source()).append(" > ").append(r.section())
                    .append(" (chunk id: ").append(r.chunkId()).append(")]\n");
                context.append(r.content()).append("\n\n");
            }
            
            context.append("=== ВОПРОС ===\n").append(userQuery);
            context.append(RAG_SYSTEM_PROMPT);

            String augmentedPrompt = context.toString();
            log.info("Augmented prompt: {} chars (original: {})", augmentedPrompt.length(), userQuery.length());
            log.info("=== RAG END ===");

            return new RagResult(augmentedPrompt, sources, hasRelevant, maxScore);
        } catch (Exception e) {
            log.error("Error during RAG augmentation: {}", e.getMessage(), e);
            log.info("=== RAG END (error) ===");
            return new RagResult(userQuery, List.of(), false, 0.0);
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

    public RerankerService getRerankerService() {
        return rerankerService;
    }

    public RagStatus getStatus(String currentModel) {
        boolean ragEnabled = appContext != null && appContext.isRagEnabled();
        
        boolean retrievalLocal = embeddingService != null && 
            embeddingService.isOllamaAvailable() && 
            embeddingService.hasOllamaModel();
        
        boolean generationLocal = currentModel != null && currentModel.startsWith("ollama:");
        
        String embeddingModel = embeddingService != null ? embeddingService.getOllamaModelName() : null;
        String rerankerModel = rerankerService != null && rerankerService.isAvailable() 
            ? rerankerService.getModelName() : null;
        boolean rerankerAvailable = rerankerService != null && rerankerService.isAvailable();
        
        int chunksCount = getChunksCount();
        
        return RagStatus.of(
            ragEnabled,
            retrievalLocal,
            generationLocal,
            embeddingModel,
            currentModel,
            rerankerModel,
            rerankerAvailable,
            chunksCount
        );
    }
}
