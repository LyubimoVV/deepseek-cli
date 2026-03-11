package com.example.deepseek.memory.repository;

import com.example.deepseek.memory.dto.WorkingMemoryDto;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface WorkingMemoryRepository {
    long save(long sessionId, String category, String key, String value, int priority) throws SQLException;
    Optional<WorkingMemoryDto> getById(long id) throws SQLException;
    Optional<WorkingMemoryDto> getByKey(long sessionId, String key) throws SQLException;
    List<WorkingMemoryDto> getBySession(long sessionId) throws SQLException;
    List<WorkingMemoryDto> getBySessionAndCategory(long sessionId, String category) throws SQLException;
    List<WorkingMemoryDto> getBySessionAndKeys(long sessionId, java.util.Set<String> keys) throws SQLException;
    void update(long id, String category, String key, String value, int priority) throws SQLException;
    void delete(long sessionId, String key) throws SQLException;
    void deleteById(long id) throws SQLException;
    void deleteAllForSession(long sessionId) throws SQLException;
}
