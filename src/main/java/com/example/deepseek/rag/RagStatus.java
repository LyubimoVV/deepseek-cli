package com.example.deepseek.rag;

public record RagStatus(
    boolean ragEnabled,
    boolean retrievalLocal,
    boolean generationLocal,
    String embeddingModel,
    String generationModel,
    String rerankerModel,
    boolean rerankerAvailable,
    int chunksCount,
    String retrievalStatus,
    String generationStatus
) {
    public static RagStatus of(
        boolean ragEnabled,
        boolean retrievalLocal,
        boolean generationLocal,
        String embeddingModel,
        String generationModel,
        String rerankerModel,
        boolean rerankerAvailable,
        int chunksCount
    ) {
        String retrievalStatus = retrievalLocal ? "local" : "unavailable";
        String generationStatus = generationLocal ? "local" : "cloud";
        
        return new RagStatus(
            ragEnabled, retrievalLocal, generationLocal,
            embeddingModel, generationModel, rerankerModel,
            rerankerAvailable, chunksCount,
            retrievalStatus, generationStatus
        );
    }
    
    public boolean isFullyLocal() {
        return ragEnabled && retrievalLocal && generationLocal;
    }
    
    public String getSummary() {
        if (!ragEnabled) {
            return "RAG disabled";
        }
        if (isFullyLocal()) {
            return "Fully local RAG";
        }
        if (retrievalLocal) {
            return "Local retrieval, cloud generation";
        }
        return "RAG partially available";
    }
}
