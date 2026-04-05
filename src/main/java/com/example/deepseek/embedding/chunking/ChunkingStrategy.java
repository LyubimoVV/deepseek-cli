package com.example.deepseek.embedding.chunking;

import com.example.deepseek.embedding.Chunk;
import java.util.List;

public interface ChunkingStrategy {
    String getName();
    List<Chunk> chunk(String content, String source, String title);
}
