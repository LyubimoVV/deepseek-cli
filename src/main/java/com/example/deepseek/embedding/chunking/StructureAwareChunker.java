package com.example.deepseek.embedding.chunking;

import com.example.deepseek.embedding.Chunk;
import com.example.deepseek.embedding.ChunkMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StructureAwareChunker implements ChunkingStrategy {
    private static final Logger log = LoggerFactory.getLogger(StructureAwareChunker.class);
    private static final int MAX_CHUNK_CHARS = 1000;
    private static final int MIN_CHUNK_CHARS = 100;
    private static final int OVERLAP_CHARS = 0;
    
    private static final Pattern MARKDOWN_HEADER = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern JAVA_CLASS = Pattern.compile("(?:^|\\n)(public|private|protected)?\\s*(?:abstract|final|static)?\\s*(class|interface|enum|record)\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern JAVA_METHOD = Pattern.compile("(?:^|\\n)\\s*(?:public|private|protected|static|final|synchronized|abstract)\\s+[\\w<>\\[\\],\\s]+\\s+(\\w+)\\s*\\([^)]*\\)", Pattern.MULTILINE);

    @Override
    public String getName() {
        return ChunkingType.STRUCTURE.name();
    }

    @Override
    public List<Chunk> chunk(String content, String source, String title) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }

        String extension = getExtension(source);
        return switch (extension) {
            case "md" -> chunkMarkdown(content, source, title);
            case "java" -> chunkJava(content, source, title);
            default -> chunkGeneric(content, source, title);
        };
    }

    private String getExtension(String source) {
        if (source == null) return "txt";
        int dotIdx = source.lastIndexOf('.');
        return dotIdx >= 0 ? source.substring(dotIdx + 1).toLowerCase() : "txt";
    }

    private List<Chunk> chunkMarkdown(String content, String source, String title) {
        List<Chunk> chunks = new ArrayList<>();
        List<Section> sections = findMarkdownSections(content);
        
        if (sections.isEmpty()) {
            return chunkGeneric(content, source, title);
        }

        int position = 0;
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            String sectionContent = content.substring(section.start, section.end);
            
            if (sectionContent.length() > MAX_CHUNK_CHARS) {
                chunks.addAll(splitLargeSection(sectionContent, source, title, section.name, position));
                position += countSplits(sectionContent.length());
            } else if (sectionContent.trim().length() >= MIN_CHUNK_CHARS) {
                ChunkMetadata metadata = ChunkMetadata.create(
                    source, title, section.name, position,
                    section.lineStart, section.lineEnd, getName()
                );
                chunks.add(new Chunk(metadata, sectionContent.trim()));
                position++;
            } else if (sectionContent.trim().length() > 0) {
                log.debug("Skipping short markdown chunk ({} chars): '{}...' in {}", 
                    sectionContent.trim().length(), 
                    sectionContent.trim().substring(0, Math.min(30, sectionContent.trim().length())),
                    source);
            }
        }

        return chunks;
    }

    private List<Section> findMarkdownSections(String content) {
        List<Section> sections = new ArrayList<>();
        String[] lines = content.split("\n");
        
        int currentStart = 0;
        int currentLineStart = 1;
        String currentName = "header";
        
        int charPos = 0;
        int lineNum = 1;

        for (String line : lines) {
            Matcher matcher = MARKDOWN_HEADER.matcher(line);
            if (matcher.matches()) {
                if (currentStart < charPos) {
                    sections.add(new Section(currentName, currentStart, charPos, currentLineStart, lineNum - 1));
                }
                currentStart = charPos;
                currentLineStart = lineNum;
                currentName = matcher.group(2).trim();
            }
            charPos += line.length() + 1;
            lineNum++;
        }

        if (currentStart < content.length()) {
            sections.add(new Section(currentName, currentStart, content.length(), currentLineStart, lineNum - 1));
        }

        return sections;
    }

    private List<Chunk> chunkJava(String content, String source, String title) {
        List<Chunk> chunks = new ArrayList<>();
        List<Section> sections = findJavaSections(content);
        
        if (sections.isEmpty()) {
            return chunkGeneric(content, source, title);
        }

        int position = 0;
        for (Section section : sections) {
            String sectionContent = content.substring(section.start, section.end);
            
            if (sectionContent.length() > MAX_CHUNK_CHARS) {
                chunks.addAll(splitLargeSection(sectionContent, source, title, section.name, position));
                position += countSplits(sectionContent.length());
            } else if (sectionContent.trim().length() >= MIN_CHUNK_CHARS) {
                ChunkMetadata metadata = ChunkMetadata.create(
                    source, title, section.name, position,
                    section.lineStart, section.lineEnd, getName()
                );
                chunks.add(new Chunk(metadata, sectionContent.trim()));
                position++;
            } else if (sectionContent.trim().length() > 0) {
                log.debug("Skipping short java chunk ({} chars): '{}...' in {}", 
                    sectionContent.trim().length(), 
                    sectionContent.trim().substring(0, Math.min(30, sectionContent.trim().length())),
                    source);
            }
        }

        return chunks;
    }

    private List<Section> findJavaSections(String content) {
        List<Section> sections = new ArrayList<>();
        String[] lines = content.split("\n");
        
        int currentStart = 0;
        int currentLineStart = 1;
        String currentName = "header";
        int braceDepth = 0;
        boolean inClass = false;
        
        int charPos = 0;
        int lineNum = 1;

        for (String line : lines) {
            Matcher classMatcher = JAVA_CLASS.matcher(line);
            if (classMatcher.find()) {
                if (currentStart < charPos && !currentName.equals("header")) {
                    sections.add(new Section(currentName, currentStart, charPos, currentLineStart, lineNum - 1));
                    currentStart = charPos;
                    currentLineStart = lineNum;
                }
                currentName = classMatcher.group(3);
                inClass = true;
            }

            if (inClass) {
                braceDepth += countChar(line, '{') - countChar(line, '}');
                if (braceDepth == 0 && line.contains("}")) {
                    sections.add(new Section(currentName, currentStart, charPos + line.length() + 1, currentLineStart, lineNum));
                    currentStart = charPos + line.length() + 1;
                    currentLineStart = lineNum + 1;
                    currentName = "body";
                    inClass = false;
                }
            }

            charPos += line.length() + 1;
            lineNum++;
        }

        if (currentStart < content.length()) {
            sections.add(new Section(currentName, currentStart, content.length(), currentLineStart, lineNum - 1));
        }

        return sections;
    }

    private int countChar(String s, char c) {
        int count = 0;
        for (char ch : s.toCharArray()) {
            if (ch == c) count++;
        }
        return count;
    }

    private List<Chunk> chunkGeneric(String content, String source, String title) {
        List<Chunk> chunks = new ArrayList<>();
        String normalizedContent = content.replace("\r\n", "\n").replace("\r", "\n");
        String[] allLines = normalizedContent.split("\n", -1);
        
        List<ParagraphInfo> paragraphs = new ArrayList<>();
        int paraStart = -1;
        StringBuilder paraContent = new StringBuilder();
        
        for (int i = 0; i < allLines.length; i++) {
            String line = allLines[i];
            if (line.trim().isEmpty()) {
                if (paraStart != -1) {
                    paragraphs.add(new ParagraphInfo(paraStart + 1, i, paraContent.toString().trim()));
                    paraStart = -1;
                    paraContent = new StringBuilder();
                }
            } else {
                if (paraStart == -1) {
                    paraStart = i;
                }
                if (paraContent.length() > 0) {
                    paraContent.append("\n");
                }
                paraContent.append(line);
            }
        }
        
        if (paraStart != -1) {
            paragraphs.add(new ParagraphInfo(paraStart + 1, allLines.length, paraContent.toString().trim()));
        }
        
        int position = 0;
        StringBuilder currentChunk = new StringBuilder();
        int chunkStartLine = 1;
        int chunkEndLine = 0;
        int skippedCount = 0;

        for (ParagraphInfo para : paragraphs) {
            if (para.content().length() > MAX_CHUNK_CHARS) {
                if (currentChunk.length() > 0) {
                    String trimmedChunk = currentChunk.toString().trim();
                    if (trimmedChunk.length() >= MIN_CHUNK_CHARS) {
                        String section = "lines " + chunkStartLine + "-" + chunkEndLine;
                        ChunkMetadata metadata = ChunkMetadata.create(
                            source, title, section, position++,
                            chunkStartLine, chunkEndLine, getName()
                        );
                        chunks.add(new Chunk(metadata, trimmedChunk));
                    } else {
                        skippedCount++;
                        log.debug("Skipping short generic chunk ({} chars)", trimmedChunk.length());
                    }
                    currentChunk = new StringBuilder();
                }
                
                chunks.addAll(splitLargeParagraphWithLines(para.content(), source, title, position, para.startLine(), para.endLine()));
                position += countSplits(para.content().length());
                chunkStartLine = para.endLine() + 1;
                continue;
            }
            
            if (currentChunk.length() + para.content().length() + 2 > MAX_CHUNK_CHARS && currentChunk.length() > 0) {
                String trimmedChunk = currentChunk.toString().trim();
                if (trimmedChunk.length() >= MIN_CHUNK_CHARS) {
                    String section = "lines " + chunkStartLine + "-" + chunkEndLine;
                    ChunkMetadata metadata = ChunkMetadata.create(
                        source, title, section, position++,
                        chunkStartLine, chunkEndLine, getName()
                    );
                    chunks.add(new Chunk(metadata, trimmedChunk));
                } else {
                    skippedCount++;
                    log.debug("Skipping short generic chunk ({} chars)", trimmedChunk.length());
                }
                currentChunk = new StringBuilder();
                chunkStartLine = para.startLine();
            }
            
            if (currentChunk.length() == 0) {
                chunkStartLine = para.startLine();
            }
            
            if (currentChunk.length() > 0) {
                currentChunk.append("\n\n");
            }
            currentChunk.append(para.content());
            chunkEndLine = para.endLine();
        }

        if (currentChunk.length() > 0) {
            String trimmedChunk = currentChunk.toString().trim();
            if (trimmedChunk.length() >= MIN_CHUNK_CHARS) {
                String section = "lines " + chunkStartLine + "-" + chunkEndLine;
                ChunkMetadata metadata = ChunkMetadata.create(
                    source, title, section, position,
                    chunkStartLine, chunkEndLine, getName()
                );
                chunks.add(new Chunk(metadata, trimmedChunk));
            } else {
                skippedCount++;
                log.debug("Skipping short final generic chunk ({} chars)", trimmedChunk.length());
            }
        }
        
        if (skippedCount > 0) {
            log.info("Skipped {} short chunks (< {} chars) in {}", skippedCount, MIN_CHUNK_CHARS, source);
        }

        return chunks;
    }
    
    private List<Chunk> splitLargeParagraph(String para, String source, String title, int startPos, int startLine) {
        List<Chunk> chunks = new ArrayList<>();
        int chunkSize = MAX_CHUNK_CHARS - 100;
        int position = startPos;
        int offset = 0;
        boolean isFirst = true;
        
        while (offset < para.length()) {
            int overlap = isFirst ? 0 : OVERLAP_CHARS;
            int start = Math.max(0, offset - overlap);
            int end = Math.min(offset + chunkSize, para.length());
            String chunkContent = para.substring(start, end);
            
            int lastSpace = chunkContent.lastIndexOf(' ');
            if (lastSpace > chunkSize / 2 && end < para.length()) {
                end = start + lastSpace + 1;
                chunkContent = para.substring(start, end);
            }

            ChunkMetadata metadata = ChunkMetadata.create(
                source, title, "lines " + startLine + "-" + (startLine + para.split("\n", -1).length - 1),
                position++, startLine, startLine, getName()
            );
            chunks.add(new Chunk(metadata, chunkContent.trim()));
            offset = end;
            isFirst = false;
        }

        return chunks;
    }

    private List<Chunk> splitLargeSection(String content, String source, String title, String section, int startPos) {
        List<Chunk> chunks = new ArrayList<>();
        int chunkSize = MAX_CHUNK_CHARS - 200;
        int position = startPos;
        int offset = 0;
        boolean isFirst = true;
        
        while (offset < content.length()) {
            int overlap = isFirst ? 0 : OVERLAP_CHARS;
            int start = Math.max(0, offset - overlap);
            int end = Math.min(offset + chunkSize, content.length());
            String chunkContent = content.substring(start, end);
            
            int newlineIdx = chunkContent.lastIndexOf('\n');
            if (newlineIdx > chunkSize / 2 && end < content.length()) {
                end = start + newlineIdx + 1;
                chunkContent = content.substring(start, end);
            }

            ChunkMetadata metadata = ChunkMetadata.create(
                source, title, section + " (lines part)",
                position++, 0, 0, getName()
            );
            chunks.add(new Chunk(metadata, chunkContent.trim()));
            offset = end;
            isFirst = false;
        }

        return chunks;
    }

    private int countSplits(int length) {
        return (int) Math.ceil((double) length / (MAX_CHUNK_CHARS - 200));
    }

    private List<Chunk> splitLargeParagraphWithLines(String para, String source, String title, int startPos, int startLine, int endLine) {
        List<Chunk> chunks = new ArrayList<>();
        int chunkSize = MAX_CHUNK_CHARS - 100;
        int position = startPos;
        int offset = 0;
        
        while (offset < para.length()) {
            int end = Math.min(offset + chunkSize, para.length());
            String chunkContent = para.substring(offset, end);
            
            int lastSpace = chunkContent.lastIndexOf(' ');
            if (lastSpace > chunkSize / 2 && end < para.length()) {
                end = offset + lastSpace + 1;
                chunkContent = para.substring(offset, end);
            }

            String section = "lines " + startLine + "-" + endLine;
            ChunkMetadata metadata = ChunkMetadata.create(
                source, title, section, position++, startLine, endLine, getName()
            );
            chunks.add(new Chunk(metadata, chunkContent.trim()));
            offset = end;
        }

        return chunks;
    }

    private record ParagraphInfo(int startLine, int endLine, String content) {}
    private record Section(String name, int start, int end, int lineStart, int lineEnd) {}
}
