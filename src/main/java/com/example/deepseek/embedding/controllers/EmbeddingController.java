package com.example.deepseek.embedding.controllers;

import com.example.deepseek.embedding.EmbeddingService;
import com.example.deepseek.embedding.index.SearchResult;
import io.javalin.http.Context;

import java.util.List;
import java.util.Map;

public class EmbeddingController {
    private final EmbeddingService embeddingService;

    public EmbeddingController(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    public void indexFile(Context ctx) {
        try {
            IndexRequest request = ctx.bodyAsClass(IndexRequest.class);
            
            if (request.path() == null || request.path().isBlank()) {
                ctx.status(400).json(Map.of("error", "path is required"));
                return;
            }

            String strategy = request.strategy() != null ? request.strategy() : "STRUCTURE";

            if (request.recursive() != null && request.recursive()) {
                List<EmbeddingService.IndexResult> results = 
                    embeddingService.indexDirectory(request.path(), strategy, true);
                ctx.json(Map.of(
                    "success", true,
                    "results", results
                ));
            } else {
                EmbeddingService.IndexResult result = embeddingService.indexFile(request.path(), strategy);
                ctx.json(Map.of(
                    "success", true,
                    "result", result
                ));
            }
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    public void search(Context ctx) {
        try {
            String query = ctx.queryParam("q");
            int k = ctx.queryParamAsClass("k", Integer.class).getOrDefault(5);
            String strategy = ctx.queryParam("strategy");

            if (query == null || query.isBlank()) {
                ctx.status(400).json(Map.of("error", "query parameter 'q' is required"));
                return;
            }

            List<SearchResult> results = embeddingService.search(query, k, strategy);
            ctx.json(Map.of(
                "success", true,
                "query", query,
                "strategy", strategy != null ? strategy : "BOTH",
                "results", results
            ));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    public void clearIndex(Context ctx) {
        try {
            String strategy = ctx.queryParam("strategy");
            embeddingService.clearIndex(strategy);
            String message = strategy != null && !strategy.isBlank() 
                ? "Index cleared for strategy: " + strategy 
                : "All indexes cleared";
            ctx.json(Map.of("success", true, "message", message));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    public void removeSource(Context ctx) {
        try {
            String source = ctx.pathParam("source");
            embeddingService.removeFromIndex(source);
            ctx.json(Map.of("success", true, "message", "Removed: " + source));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    public void getStats(Context ctx) {
        try {
            EmbeddingService.IndexStats stats = embeddingService.getStats();
            ctx.json(Map.of(
                "success", true,
                "stats", stats,
                "ollama", Map.of(
                    "available", embeddingService.isOllamaAvailable(),
                    "hasModel", embeddingService.hasOllamaModel(),
                    "model", embeddingService.getOllamaModelName()
                )
            ));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    public void compareStrategies(Context ctx) {
        try {
            String path = ctx.queryParam("path");
            
            if (path == null || path.isBlank()) {
                ctx.status(400).json(Map.of("error", "query parameter 'path' is required"));
                return;
            }

            EmbeddingService.ChunkingComparison comparison = embeddingService.compareStrategies(path);
            ctx.json(Map.of(
                "success", true,
                "comparison", comparison
            ));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    public void checkOllama(Context ctx) {
        ctx.json(Map.of(
            "available", embeddingService.isOllamaAvailable(),
            "hasModel", embeddingService.hasOllamaModel(),
            "model", embeddingService.getOllamaModelName()
        ));
    }
}

record IndexRequest(
    String path,
    String strategy,
    Boolean recursive
) {}
