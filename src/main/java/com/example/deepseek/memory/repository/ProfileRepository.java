package com.example.deepseek.memory.repository;

import com.example.deepseek.memory.dto.ProfileDto;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface ProfileRepository {
    long create(String name, String description, String systemPrompt, String personalization) throws SQLException;
    Optional<ProfileDto> getById(long id) throws SQLException;
    Optional<ProfileDto> getByName(String name) throws SQLException;
    List<ProfileDto> getAll() throws SQLException;
    void update(long id, String name, String description, String systemPrompt, String personalization) throws SQLException;
    void delete(long id) throws SQLException;
    Optional<ProfileDto> getDefaultProfile() throws SQLException;
}
