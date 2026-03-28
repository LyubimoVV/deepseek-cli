package com.example.deepseek.embedding;

import com.example.deepseek.embedding.chunking.*;
import com.example.deepseek.embedding.index.*;
import com.example.deepseek.embedding.ollama.OllamaClient;
import com.example.deepseek.embedding.repository.ChunkMetadataRepository;
import com.example.deepseek.embedding.repository.ChunkMetadataRepository.ChunkMetadataEntry;
import com.example.deepseek.embedding.repository.ChunkMetadataRepository.EmbeddingEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class EmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final int EMBEDDING_DIMENSION = 768;
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        "md", "txt", "java", "json", "xml", "yaml", "yml", "properties", "sql", "html", "css", "js"
    );

    private final OllamaClient ollamaClient;
    private final Map<String, VectorIndex> indexes;
    private final ChunkMetadataRepository metadataRepository;
    private final Map<String, ChunkingStrategy> chunkingStrategies;

    public EmbeddingService(OllamaClient ollamaClient, ChunkMetadataRepository metadataRepository) {
        this.ollamaClient = ollamaClient;
        this.metadataRepository = metadataRepository;
        this.indexes = new HashMap<>();
        this.indexes.put(ChunkingType.FIXED.name(), new SmileVectorIndex(EMBEDDING_DIMENSION));
        this.indexes.put(ChunkingType.STRUCTURE.name(), new SmileVectorIndex(EMBEDDING_DIMENSION));
        this.chunkingStrategies = Map.of(
            ChunkingType.FIXED.name(), new FixedSizeChunker(),
            ChunkingType.STRUCTURE.name(), new StructureAwareChunker()
        );
    }

    public void loadIndex() {
        log.info("Loading embeddings from database...");
        
        for (String strategy : List.of(ChunkingType.FIXED.name(), ChunkingType.STRUCTURE.name())) {
            List<EmbeddingEntry> entries = metadataRepository.loadEmbeddingsByStrategy(strategy);
            if (!entries.isEmpty()) {
                VectorIndex index = indexes.get(strategy);
                List<String> chunkIds = entries.stream().map(EmbeddingEntry::chunkId).toList();
                List<float[]> embeddings = entries.stream().map(EmbeddingEntry::embedding).toList();
                index.addAll(chunkIds, embeddings);
                log.info("Loaded {} embeddings for strategy {}", entries.size(), strategy);
            }
        }
        
        int total = indexes.values().stream().mapToInt(VectorIndex::size).sum();
        log.info("Total embeddings loaded: {}", total);
    }

    public IndexResult indexFile(String filePath, String strategyName) throws IOException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File not found: " + filePath);
        }

        String content = Files.readString(path);
        String title = path.getFileName().toString();
        String source = getRelativePath(path);

        ChunkingStrategy strategy = chunkingStrategies.get(strategyName);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown chunking strategy: " + strategyName);
        }

        List<Chunk> chunks = strategy.chunk(content, source, title);
        log.info("Created {} chunks for {}", chunks.size(), source);

        VectorIndex index = indexes.get(strategyName);
        
        int batchSize = 50;
        int totalChunks = chunks.size();
        
        for (int i = 0; i < totalChunks; i += batchSize) {
            int end = Math.min(i + batchSize, totalChunks);
            List<Chunk> batch = chunks.subList(i, end);
            
            List<String> chunkIds = new ArrayList<>();
            List<float[]> embeddings = new ArrayList<>();
            List<ChunkMetadataEntry> metadataEntries = new ArrayList<>();

            for (Chunk chunk : batch) {
                try {
                    float[] embedding = ollamaClient.embed(chunk.content());
                    chunkIds.add(chunk.metadata().chunkId());
                    embeddings.add(embedding);
                    metadataEntries.add(new ChunkMetadataEntry(chunk.metadata(), chunk.content()));
                } catch (Exception e) {
                    log.warn("Failed to embed chunk {}: {}", chunk.metadata().chunkId(), e.getMessage());
                }
            }

            if (!chunkIds.isEmpty()) {
                index.addAll(chunkIds, embeddings);
                
                try {
                    metadataRepository.saveAll(metadataEntries);
                    metadataRepository.saveAllEmbeddings(chunkIds, embeddings, strategyName);
                } catch (Exception e) {
                    log.error("Failed to save batch {}-{}: {}", i, end, e.getMessage());
                    throw new RuntimeException("Failed to save embeddings batch: " + e.getMessage(), e);
                }
            }
            
            log.info("Indexed batch {}/{} (chunks {}-{})", (i/batchSize + 1), (totalChunks + batchSize - 1)/batchSize, i, end-1);
        }

        log.info("Indexed {} chunks from {} with strategy {}", chunks.size(), source, strategyName);

        return new IndexResult(
            source,
            chunks.size(),
            strategyName,
            chunks.stream().mapToInt(c -> c.content().length()).sum()
        );
    }

    public List<IndexResult> indexDirectory(String dirPath, String strategyName, boolean recursive) throws IOException {
        Path startDir = Paths.get(dirPath);
        if (!Files.isDirectory(startDir)) {
            throw new IllegalArgumentException("Not a directory: " + dirPath);
        }

        List<IndexResult> results = new ArrayList<>();
        int maxDepth = recursive ? Integer.MAX_VALUE : 1;

        try (Stream<Path> paths = Files.walk(startDir, maxDepth)) {
            paths.filter(Files::isRegularFile)
                .filter(this::isSupportedFile)
                .forEach(path -> {
                    try {
                        results.add(indexFile(path.toString(), strategyName));
                    } catch (Exception e) {
                        log.warn("Failed to index file {}: {}", path, e.getMessage());
                    }
                });
        }

        return results;
    }

    public List<SearchResult> search(String query, int k, String strategy) {
        float[] queryEmbedding = ollamaClient.embed(query);
        
        List<SearchResult> results = new ArrayList<>();
        
        if (strategy == null || strategy.isBlank() || "BOTH".equalsIgnoreCase(strategy)) {
            int kPerStrategy = Math.max(1, k / 2);
            results.addAll(searchInIndex(queryEmbedding, kPerStrategy, ChunkingType.FIXED.name()));
            results.addAll(searchInIndex(queryEmbedding, kPerStrategy, ChunkingType.STRUCTURE.name()));
            results.sort((a, b) -> Double.compare(b.score(), a.score()));
            if (results.size() > k) {
                results = results.subList(0, k);
            }
        } else {
            results.addAll(searchInIndex(queryEmbedding, k, strategy));
        }

        return results;
    }

    private List<SearchResult> searchInIndex(float[] queryEmbedding, int k, String strategy) {
        VectorIndex index = indexes.get(strategy);
        if (index == null || index.isEmpty()) {
            return List.of();
        }

        List<SearchResult> searchResults = index.search(queryEmbedding, k);

        return searchResults.stream()
            .map(r -> {
                Optional<ChunkMetadataEntry> entry = metadataRepository.findById(r.chunkId());
                if (entry.isPresent()) {
                    ChunkMetadata m = entry.get().metadata();
                    return new SearchResult(
                        r.chunkId(),
                        r.score(),
                        entry.get().content(),
                        m.source(),
                        m.title(),
                        m.section()
                    );
                }
                return r;
            })
            .collect(Collectors.toList());
    }

    public ChunkingComparison compareStrategies(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        String content = Files.readString(path);
        String title = path.getFileName().toString();
        String source = getRelativePath(path);

        List<Chunk> fixedChunks = chunkingStrategies.get(ChunkingType.FIXED.name()).chunk(content, source, title);
        List<Chunk> structureChunks = chunkingStrategies.get(ChunkingType.STRUCTURE.name()).chunk(content, source, title);

        return new ChunkingComparison(
            source,
            new ChunkingStats(
                ChunkingType.FIXED.name(),
                fixedChunks.size(),
                calculateAvgChunkSize(fixedChunks),
                calculateMinChunkSize(fixedChunks),
                calculateMaxChunkSize(fixedChunks)
            ),
            new ChunkingStats(
                ChunkingType.STRUCTURE.name(),
                structureChunks.size(),
                calculateAvgChunkSize(structureChunks),
                calculateMinChunkSize(structureChunks),
                calculateMaxChunkSize(structureChunks)
            )
        );
    }

    public void removeFromIndex(String source) {
        List<ChunkMetadataEntry> entries = metadataRepository.findBySource(source);
        Map<String, List<String>> byStrategy = entries.stream()
            .collect(Collectors.groupingBy(
                e -> e.metadata().strategy(),
                Collectors.mapping(e -> e.metadata().chunkId(), Collectors.toList())
            ));
        
        for (Map.Entry<String, List<String>> entry : byStrategy.entrySet()) {
            VectorIndex index = indexes.get(entry.getKey());
            if (index != null) {
                for (String chunkId : entry.getValue()) {
                    index.remove(chunkId);
                }
            }
        }
        
        metadataRepository.deleteBySource(source);
    }

    public void clearIndex(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            for (VectorIndex index : indexes.values()) {
                index.clear();
            }
            metadataRepository.deleteAllEmbeddings();
        } else {
            VectorIndex index = indexes.get(strategy);
            if (index != null) {
                index.clear();
            }
            metadataRepository.deleteEmbeddingsByStrategy(strategy);
        }
    }

    public void clearAllIndexes() {
        clearIndex(null);
    }

    public IndexStats getStats() {
        VectorIndex fixedIndex = indexes.get(ChunkingType.FIXED.name());
        VectorIndex structureIndex = indexes.get(ChunkingType.STRUCTURE.name());
        
        return new IndexStats(
            fixedIndex.size() + structureIndex.size(),
            metadataRepository.count(),
            fixedIndex.size(),
            structureIndex.size(),
            metadataRepository.findAllSources(),
            metadataRepository.findSourcesByStrategy(ChunkingType.FIXED.name()),
            metadataRepository.findSourcesByStrategy(ChunkingType.STRUCTURE.name()),
            EMBEDDING_DIMENSION
        );
    }

    public StrategyStats getStatsByStrategy(String strategy) {
        VectorIndex index = indexes.get(strategy);
        if (index == null) {
            return new StrategyStats(strategy, 0, List.of());
        }
        return new StrategyStats(
            strategy,
            index.size(),
            metadataRepository.findSourcesByStrategy(strategy)
        );
    }

    public boolean isOllamaAvailable() {
        return ollamaClient.isAvailable();
    }

    public boolean hasOllamaModel() {
        return ollamaClient.hasModel();
    }

    public String getOllamaModelName() {
        return ollamaClient.getModelName();
    }

    private boolean isSupportedFile(Path path) {
        String fileName = path.getFileName().toString();
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx < 0) return false;
        String ext = fileName.substring(dotIdx + 1).toLowerCase();
        return SUPPORTED_EXTENSIONS.contains(ext);
    }
    
    private String getRelativePath(Path path) {
        Path cwd = Paths.get("").toAbsolutePath();
        Path absolutePath = path.toAbsolutePath();
        try {
            return cwd.relativize(absolutePath).toString().replace('\\', '/');
        } catch (Exception e) {
            return path.toString();
        }
    }

    private double calculateAvgChunkSize(List<Chunk> chunks) {
        return chunks.isEmpty() ? 0 :
            chunks.stream().mapToInt(c -> c.content().length()).average().orElse(0);
    }

    private int calculateMinChunkSize(List<Chunk> chunks) {
        return chunks.isEmpty() ? 0 :
            chunks.stream().mapToInt(c -> c.content().length()).min().orElse(0);
    }

    private int calculateMaxChunkSize(List<Chunk> chunks) {
        return chunks.isEmpty() ? 0 :
            chunks.stream().mapToInt(c -> c.content().length()).max().orElse(0);
    }

    public record IndexResult(
        String source,
        int chunkCount,
        String strategy,
        int totalChars
    ) {}

    public record ChunkingComparison(
        String source,
        ChunkingStats fixed,
        ChunkingStats structure
    ) {}

    public record ChunkingStats(
        String strategy,
        int chunkCount,
        double avgChunkSize,
        int minChunkSize,
        int maxChunkSize
    ) {}

    public record IndexStats(
        int totalVectorCount,
        int metadataCount,
        int fixedCount,
        int structureCount,
        List<String> allSources,
        List<String> fixedSources,
        List<String> structureSources,
        int dimension
    ) {}

    public record StrategyStats(
        String strategy,
        int vectorCount,
        List<String> sources
    ) {}
}
