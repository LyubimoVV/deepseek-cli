package com.example.deepseek.memory.repository;

import com.example.deepseek.memory.dto.ProfileDto;
import com.example.deepseek.memory.repository.impl.ProfileRepositoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileRepositoryTest {

    private ProfileRepository repo;

    @BeforeEach
    void setUp() {
        repo = new ProfileRepositoryImpl();
    }

    @AfterEach
    void tearDown() throws SQLException {
        var conn = com.example.deepseek.db.DatabaseConfig.getConnection();
        try (var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM profiles WHERE name LIKE 'Уникальное имя%' OR name LIKE 'Для удаления%' OR name LIKE 'Старое имя%' OR name LIKE 'Профиль%' OR name LIKE 'Тестовый%' OR name LIKE 'Новое имя%'");
        }
    }

    @Test
    void save_and_retrieve() throws Exception {
        long id = repo.create("Тестовый разработчик", "Профиль разработчика", "Системный промпт", "{\"theme\": \"dark\"}");

        assertThat(id).isPositive();

        Optional<ProfileDto> result = repo.getById(id);

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Тестовый разработчик");
        assertThat(result.get().description()).isEqualTo("Профиль разработчика");
    }

    @Test
    void get_by_id() throws Exception {
        long id = repo.create("Профиль 1", null, null, null);

        Optional<ProfileDto> result = repo.getById(id);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(id);
    }

    @Test
    void get_all() throws Exception {
        repo.create("Профиль 1", null, null, null);
        repo.create("Профиль 2", null, null, null);
        repo.create("Профиль 3", null, null, null);

        List<ProfileDto> profiles = repo.getAll();

        assertThat(profiles).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void update() throws Exception {
        long id = repo.create("Старое имя", null, null, null);

        repo.update(id, "Новое имя", "Описание", "Промпт", "{\"key\": \"value\"}");

        Optional<ProfileDto> result = repo.getById(id);
        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Новое имя");
        assertThat(result.get().description()).isEqualTo("Описание");
    }

    @Test
    void delete() throws Exception {
        long id = repo.create("Для удаления", null, null, null);

        repo.delete(id);

        Optional<ProfileDto> result = repo.getById(id);
        assertThat(result).isEmpty();
    }

    @Test
    void default_profile_exists() throws Exception {
        Optional<ProfileDto> defaultProfile = repo.getDefaultProfile();

        assertThat(defaultProfile).isPresent();
        assertThat(defaultProfile.get().name()).isEqualTo("Default");
    }

    @Test
    void get_by_name() throws Exception {
        repo.create("Уникальное имя", null, null, null);

        Optional<ProfileDto> result = repo.getByName("Уникальное имя");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Уникальное имя");
    }
}
