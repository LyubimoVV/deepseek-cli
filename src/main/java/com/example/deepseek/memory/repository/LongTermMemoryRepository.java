package com.example.deepseek.memory.repository;

import com.example.deepseek.memory.dto.LongTermMemoryDto;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface LongTermMemoryRepository {
    long save(long profileId, String category, String key, String value, int priority) throws SQLException;
    Optional<LongTermMemoryDto> getById(long id) throws SQLException;
    Optional<LongTermMemoryDto> getByKey(long profileId, String key) throws SQLException;
    List<LongTermMemoryDto> getByProfile(long profileId) throws SQLException;
    List<LongTermMemoryDto> getByProfileAndCategory(long profileId, String category) throws SQLException;
    List<LongTermMemoryDto> getByProfileAndKeys(long profileId, java.util.Set<String> keys) throws SQLException;
    void update(long id, String category, String key, String value, int priority) throws SQLException;
    void delete(long profileId, String key) throws SQLException;
    void deleteById(long id) throws SQLException;
    void deleteAllForProfile(long profileId) throws SQLException;
}
