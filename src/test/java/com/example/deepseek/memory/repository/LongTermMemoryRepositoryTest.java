package com.example.deepseek.memory.repository;

import com.example.deepseek.memory.dto.LongTermMemoryDto;
import com.example.deepseek.memory.repository.impl.LongTermMemoryRepositoryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LongTermMemoryRepositoryTest {

    private LongTermMemoryRepository repo;
    private com.example.deepseek.memory.repository.impl.ProfileRepositoryImpl profileRepo;

    private long profileId1;
    private long profileId2;
    private long profileId3;

    @BeforeEach
    void setUp() throws Exception {
        repo = new LongTermMemoryRepositoryImpl();
        profileRepo = new com.example.deepseek.memory.repository.impl.ProfileRepositoryImpl();

        profileId1 = profileRepo.create("Test Profile 1", null, null, null);
        profileId2 = profileRepo.create("Test Profile 2", null, null, null);
        profileId3 = profileRepo.create("Test Profile 3", null, null, null);
    }

    @AfterEach
    void cleanUp() throws SQLException {
        var conn = com.example.deepseek.db.DatabaseConfig.getConnection();
        try (var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM long_term_memory WHERE profile_id IN (" + profileId1 + ", " + profileId2 + ", " + profileId3 + ")");
        }
    }

    @AfterEach
    void tearDownProfiles() throws SQLException {
        var conn = com.example.deepseek.db.DatabaseConfig.getConnection();
        try (var stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM profiles WHERE name IN ('Test Profile 1', 'Test Profile 2', 'Test Profile 3')");
        }
    }

    @Test
    void save_and_retrieve() throws Exception {
        long id = repo.save(profileId1, "preferences", "language", "русский", 1);

        assertThat(id).isPositive();

        Optional<LongTermMemoryDto> result = repo.getById(id);
        assertThat(result).isPresent();
        assertThat(result.get().profileId()).isEqualTo(profileId1);
        assertThat(result.get().category()).isEqualTo("preferences");
        assertThat(result.get().key()).isEqualTo("language");
        assertThat(result.get().value()).isEqualTo("русский");
    }

    @Test
    void getByProfile() throws Exception {
        repo.save(profileId1, "preferences", "lang", "русский", 1);
        repo.save(profileId1, "preferences", "theme", "dark", 1);
        repo.save(profileId2, "preferences", "lang", "english", 1);

        List<LongTermMemoryDto> profile1 = repo.getByProfile(profileId1);
        List<LongTermMemoryDto> profile2 = repo.getByProfile(profileId2);

        assertThat(profile1).hasSize(2);
        assertThat(profile2).hasSize(1);
    }

    @Test
    void getByProfileAndCategory() throws Exception {
        repo.save(profileId1, "preferences", "lang", "русский", 1);
        repo.save(profileId1, "preferences", "theme", "dark", 1);
        repo.save(profileId1, "knowledge", "fact", "value", 1);

        List<LongTermMemoryDto> items = repo.getByProfileAndCategory(profileId1, "preferences");

        assertThat(items).hasSize(2);
        assertThat(items).allMatch(item -> item.category().equals("preferences"));
    }

    @Test
    void getByProfileAndKeys() throws Exception {
        repo.save(profileId1, "preferences", "lang", "русский", 1);
        repo.save(profileId1, "preferences", "theme", "dark", 1);
        repo.save(profileId1, "preferences", "font", "size", 1);

        List<LongTermMemoryDto> items = repo.getByProfileAndKeys(profileId1, java.util.Set.of("lang", "theme"));

        assertThat(items).hasSize(2);
        assertThat(items).anyMatch(item -> item.key().equals("lang"));
        assertThat(items).anyMatch(item -> item.key().equals("theme"));
        assertThat(items).noneMatch(item -> item.key().equals("font"));
    }

    @Test
    void deleteAllForProfile() throws Exception {
        repo.save(profileId1, "prefs", "key1", "value1", 1);
        repo.save(profileId1, "prefs", "key2", "value2", 1);
        repo.save(profileId2, "prefs", "key3", "value3", 1);

        repo.deleteAllForProfile(profileId1);

        List<LongTermMemoryDto> profile1 = repo.getByProfile(profileId1);
        List<LongTermMemoryDto> profile2 = repo.getByProfile(profileId2);

        assertThat(profile1).isEmpty();
        assertThat(profile2).hasSize(1);
    }

    @Test
    void deleteByKey() throws Exception {
        repo.save(profileId1, "prefs", "lang", "русский", 1);

        repo.delete(profileId1, "lang");

        Optional<LongTermMemoryDto> result = repo.getByKey(profileId1, "lang");
        assertThat(result).isEmpty();
    }

    @Test
    void update() throws Exception {
        long id = repo.save(profileId1, "prefs", "lang", "русский", 1);

        repo.update(id, "preferences", "lang", "english", 2);

        Optional<LongTermMemoryDto> result = repo.getById(id);
        assertThat(result).isPresent();
        assertThat(result.get().category()).isEqualTo("preferences");
        assertThat(result.get().value()).isEqualTo("english");
        assertThat(result.get().priority()).isEqualTo(2);
    }

    @Test
    void save_updates_on_duplicate_key() throws Exception {
        repo.save(profileId1, "prefs", "lang", "русский", 1);

        long id2 = repo.save(profileId1, "prefs", "lang", "english", 2);

        Optional<LongTermMemoryDto> result = repo.getByKey(profileId1, "lang");
        assertThat(result).isPresent();
        assertThat(result.get().value()).isEqualTo("english");
        assertThat(result.get().priority()).isEqualTo(2);
    }
}
