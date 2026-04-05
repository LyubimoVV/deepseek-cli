package com.example.deepseek.embedding.chunking;

import com.example.deepseek.embedding.Chunk;
import com.example.deepseek.embedding.ChunkMetadata;
import java.util.ArrayList;
import java.util.List;

public class FixedSizeChunker implements ChunkingStrategy {
    private final int chunkSize;
    private final int overlap;
    private static final int CHARS_PER_TOKEN = 2;

    public FixedSizeChunker(int chunkSizeTokens, int overlapTokens) {
        this.chunkSize = chunkSizeTokens * CHARS_PER_TOKEN;
        this.overlap = overlapTokens * CHARS_PER_TOKEN;
    }

    public FixedSizeChunker() {
        this(400, 40);
    }

    @Override
    public String getName() {
        return ChunkingType.FIXED.name();
    }

    @Override
    public List<Chunk> chunk(String content, String source, String title) {
        List<Chunk> chunks = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return chunks;
        }

        String[] lines = content.split("\n", -1);
        StringBuilder currentChunk = new StringBuilder();
        int chunkStartLine = 1;
        int currentLine = 1;
        int position = 0;

        for (String line : lines) {
            if (line.length() > chunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(createChunk(currentChunk.toString(), source, title, position++, chunkStartLine, currentLine - 1));
                    String overlapText = getOverlapText(currentChunk.toString());
                    chunkStartLine = currentLine - countLines(overlapText);
                    currentChunk = new StringBuilder(overlapText);
                }
                
                List<String> parts = splitLongLine(line);
                for (String part : parts) {
                    chunks.add(createChunk(part, source, title, position++, currentLine, currentLine));
                }
                currentLine++;
                continue;
            }
            
            if (currentChunk.length() + line.length() + 1 > chunkSize && currentChunk.length() > 0) {
                chunks.add(createChunk(currentChunk.toString(), source, title, position++, chunkStartLine, currentLine - 1));
                
                String overlapText = getOverlapText(currentChunk.toString());
                chunkStartLine = currentLine - countLines(overlapText);
                currentChunk = new StringBuilder(overlapText);
            }
            currentChunk.append(line).append("\n");
            currentLine++;
        }

        if (currentChunk.length() > 0) {
            chunks.add(createChunk(currentChunk.toString(), source, title, position, chunkStartLine, currentLine - 1));
        }

        return chunks;
    }
    
    private List<String> splitLongLine(String line) {
        List<String> parts = new ArrayList<>();
        int offset = 0;
        
        while (offset < line.length()) {
            int end = Math.min(offset + chunkSize, line.length());
            String part = line.substring(offset, end);
            
            if (end < line.length()) {
                int lastSpace = part.lastIndexOf(' ');
                int lastDot = part.lastIndexOf('.');
                int lastComma = part.lastIndexOf(',');
                int breakPoint = Math.max(lastSpace, Math.max(lastDot, lastComma));
                
                if (breakPoint > chunkSize / 2) {
                    end = offset + breakPoint + 1;
                    part = line.substring(offset, end);
                }
            }
            
            parts.add(part.trim());
            offset = end;
        }
        
        return parts;
    }

    private String getOverlapText(String text) {
        if (overlap <= 0) return "";
        int start = Math.max(0, text.length() - overlap);
        String overlapText = text.substring(start);
        int newlineIdx = overlapText.indexOf('\n');
        return newlineIdx >= 0 ? overlapText.substring(newlineIdx + 1) : overlapText;
    }

    private int countLines(String text) {
        return text.isEmpty() ? 0 : text.split("\n", -1).length;
    }

    private Chunk createChunk(String content, String source, String title, int position, int startLine, int endLine) {
        String section = extractSection(content);
        ChunkMetadata metadata = ChunkMetadata.create(source, title, section, position, startLine, endLine, getName());
        return new Chunk(metadata, content.trim());
    }

    private String extractSection(String content) {
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("#")) {
                return line.replaceAll("^#+\\s*", "");
            }
        }
        return "body";
    }
}
