package com.example.deepseek.embedding.repository;

import com.example.deepseek.embedding.ChunkMetadata;
import com.example.deepseek.db.DatabaseConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ChunkMetadataRepository {
    private final DatabaseConfig dbConfig;

    public ChunkMetadataRepository(DatabaseConfig dbConfig) {
        this.dbConfig = dbConfig;
        initTables();
    }

    private void initTables() {
        try (Connection conn = dbConfig.getConnection();
             Statement stmt = conn.createStatement()) {
            
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS chunk_metadata (
                    chunk_id TEXT PRIMARY KEY,
                    source TEXT NOT NULL,
                    title TEXT,
                    section TEXT,
                    position INTEGER,
                    start_line INTEGER,
                    end_line INTEGER,
                    strategy TEXT,
                    content TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
            
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chunk_source ON chunk_metadata(source)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_chunk_strategy ON chunk_metadata(strategy)");
            
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS chunk_embeddings (
                    chunk_id TEXT PRIMARY KEY,
                    embedding BLOB NOT NULL,
                    strategy TEXT NOT NULL,
                    FOREIGN KEY (chunk_id) REFERENCES chunk_metadata(chunk_id) ON DELETE CASCADE
                )
                """);
            
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_embedding_strategy ON chunk_embeddings(strategy)");
            
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize tables", e);
        }
    }

    public void save(ChunkMetadata metadata, String content) {
        String sql = """
            INSERT OR REPLACE INTO chunk_metadata 
            (chunk_id, source, title, section, position, start_line, end_line, strategy, content)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        
        try (Connection conn = dbConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, metadata.chunkId());
            ps.setString(2, metadata.source());
            ps.setString(3, metadata.title());
            ps.setString(4, metadata.section());
            ps.setInt(5, metadata.position());
            ps.setInt(6, metadata.startLine());
            ps.setInt(7, metadata.endLine());
            ps.setString(8, metadata.strategy());
            ps.setString(9, content);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save chunk metadata", e);
        }
    }

    public void saveAll(List<ChunkMetadataEntry> entries) {
        String sql = """
            INSERT OR REPLACE INTO chunk_metadata 
            (chunk_id, source, title, section, position, start_line, end_line, strategy, content)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        
        try (Connection conn = dbConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ChunkMetadataEntry entry : entries) {
                ChunkMetadata m = entry.metadata();
                ps.setString(1, m.chunkId());
                ps.setString(2, m.source());
                ps.setString(3, m.title());
                ps.setString(4, m.section());
                ps.setInt(5, m.position());
                ps.setInt(6, m.startLine());
                ps.setInt(7, m.endLine());
                ps.setString(8, m.strategy());
                ps.setString(9, entry.content());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save chunk metadata batch", e);
        }
    }

    public void saveEmbedding(String chunkId, float[] embedding, String strategy) {
        String sql = "INSERT OR REPLACE INTO chunk_embeddings (chunk_id, embedding, strategy) VALUES (?, ?, ?)";
        
        try (Connection conn = dbConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, chunkId);
            ps.setBlob(2, new ByteArrayInputStream(serializeEmbedding(embedding)));
            ps.setString(3, strategy);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save embedding", e);
        }
    }

    public void saveAllEmbeddings(List<String> chunkIds, List<float[]> embeddings, String strategy) {
        String sql = "INSERT OR REPLACE INTO chunk_embeddings (chunk_id, embedding, strategy) VALUES (?, ?, ?)";
        
        try (Connection conn = dbConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < chunkIds.size(); i++) {
                ps.setString(1, chunkIds.get(i));
                ps.setBlob(2, new ByteArrayInputStream(serializeEmbedding(embeddings.get(i))));
                ps.setString(3, strategy);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save embeddings batch", e);
        }
    }

    public List<EmbeddingEntry> loadEmbeddingsByStrategy(String strategy) {
        String sql = "SELECT chunk_id, embedding FROM chunk_embeddings WHERE strategy = ?";
        
        try (Connection conn = dbConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, strategy);
            ResultSet rs = ps.executeQuery();
            List<EmbeddingEntry> result = new ArrayList<>();
            while (rs.next()) {
                String chunkId = rs.getString("chunk_id");
                byte[] bytes = rs.getBytes("embedding");
                float[] embedding = deserializeEmbedding(bytes);
                result.add(new EmbeddingEntry(chunkId, embedding));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load embeddings by strategy", e);
        }
    }

    public void deleteEmbeddingsByStrategy(String strategy) {
        String deleteEmbeddings = "DELETE FROM chunk_embeddings WHERE strategy = ?";
        String deleteMetadata = "DELETE FROM chunk_metadata WHERE strategy = ?";
        
        try (Connection conn = dbConfig.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(deleteEmbeddings)) {
                ps.setString(1, strategy);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(deleteMetadata)) {
                ps.setString(1, strategy);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete embeddings by strategy", e);
        }
    }

    public void deleteAllEmbeddings() {
        try (Connection conn = dbConfig.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM chunk_embeddings");
            stmt.execute("DELETE FROM chunk_metadata");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete all embeddings", e);
        }
    }

    private byte[] serializeEmbedding(float[] embedding) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(embedding.length);
            for (float f : embedding) {
                dos.writeFloat(f);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize embedding", e);
        }
    }

    private float[] deserializeEmbedding(byte[] bytes) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             DataInputStream dis = new DataInputStream(bais)) {
            int length = dis.readInt();
            float[] embedding = new float[length];
            for (int i = 0; i < length; i++) {
                embedding[i] = dis.readFloat();
            }
            return embedding;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize embedding", e);
        }
    }

    public Optional<ChunkMetadataEntry> findById(String chunkId) {
        String sql = "SELECT * FROM chunk_metadata WHERE chunk_id = ?";
        
        try (Connection conn = dbConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, chunkId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(mapToEntry(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find chunk by id", e);
        }
    }

    public List<ChunkMetadataEntry> findBySource(String source) {
        String sql = "SELECT * FROM chunk_metadata WHERE source = ? ORDER BY position";
        
        try (Connection conn = dbConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, source);
            ResultSet rs = ps.executeQuery();
            List<ChunkMetadataEntry> result = new ArrayList<>();
            while (rs.next()) {
                result.add(mapToEntry(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find chunks by source", e);
        }
    }

    public List<ChunkMetadataEntry> findByStrategy(String strategy) {
        String sql = "SELECT * FROM chunk_metadata WHERE strategy = ? ORDER BY source, position";
        
        try (Connection conn = dbConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, strategy);
            ResultSet rs = ps.executeQuery();
            List<ChunkMetadataEntry> result = new ArrayList<>();
            while (rs.next()) {
                result.add(mapToEntry(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find chunks by strategy", e);
        }
    }

    public List<ChunkMetadataEntry> findAll() {
        String sql = "SELECT * FROM chunk_metadata ORDER BY source, position";
        
        try (Connection conn = dbConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            List<ChunkMetadataEntry> result = new ArrayList<>();
            while (rs.next()) {
                result.add(mapToEntry(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find all chunks", e);
        }
    }

    public void deleteBySource(String source) {
        String deleteEmbeddings = """
            DELETE FROM chunk_embeddings WHERE chunk_id IN 
            (SELECT chunk_id FROM chunk_metadata WHERE source = ?)
            """;
        String deleteMetadata = "DELETE FROM chunk_metadata WHERE source = ?";
        
        try (Connection conn = dbConfig.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(deleteEmbeddings)) {
                ps.setString(1, source);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(deleteMetadata)) {
                ps.setString(1, source);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete chunks by source", e);
        }
    }

    public void deleteAll() {
        try (Connection conn = dbConfig.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM chunk_embeddings");
            stmt.execute("DELETE FROM chunk_metadata");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete all chunks", e);
        }
    }

    public List<String> findAllSources() {
        String sql = "SELECT DISTINCT source FROM chunk_metadata";
        
        try (Connection conn = dbConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            List<String> result = new ArrayList<>();
            while (rs.next()) {
                result.add(rs.getString("source"));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find all sources", e);
        }
    }

    public List<String> findSourcesByStrategy(String strategy) {
        String sql = "SELECT DISTINCT source FROM chunk_metadata WHERE strategy = ?";
        
        try (Connection conn = dbConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, strategy);
            ResultSet rs = ps.executeQuery();
            List<String> result = new ArrayList<>();
            while (rs.next()) {
                result.add(rs.getString("source"));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find sources by strategy", e);
        }
    }

    public int count() {
        String sql = "SELECT COUNT(*) FROM chunk_metadata";
        
        try (Connection conn = dbConfig.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count chunks", e);
        }
    }

    public int countByStrategy(String strategy) {
        String sql = "SELECT COUNT(*) FROM chunk_metadata WHERE strategy = ?";
        
        try (Connection conn = dbConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, strategy);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count chunks by strategy", e);
        }
    }

    private ChunkMetadataEntry mapToEntry(ResultSet rs) throws SQLException {
        ChunkMetadata metadata = new ChunkMetadata(
            rs.getString("chunk_id"),
            rs.getString("source"),
            rs.getString("title"),
            rs.getString("section"),
            rs.getInt("position"),
            rs.getInt("start_line"),
            rs.getInt("end_line"),
            rs.getString("strategy")
        );
        return new ChunkMetadataEntry(metadata, rs.getString("content"));
    }

    public record ChunkMetadataEntry(ChunkMetadata metadata, String content) {}
    
    public record EmbeddingEntry(String chunkId, float[] embedding) {}
}
