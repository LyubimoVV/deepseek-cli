package com.example.deepseek.memory;

import com.example.deepseek.db.SessionRepository;
import com.example.deepseek.memory.dto.LongTermMemoryDto;
import com.example.deepseek.memory.dto.WorkingMemoryDto;
import com.example.deepseek.memory.repository.LongTermMemoryRepository;
import com.example.deepseek.memory.repository.WorkingMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private static final int MAX_KEY_LENGTH = 100;
    private static final int MAX_VALUE_LENGTH = 10_000;

    private final WorkingMemoryRepository workingRepo;
    private final LongTermMemoryRepository longTermRepo;
    private final SessionRepository sessionRepository;

    public MemoryService(WorkingMemoryRepository workingRepo, LongTermMemoryRepository longTermRepo, SessionRepository sessionRepository) {
        this.workingRepo = workingRepo;
        this.longTermRepo = longTermRepo;
        this.sessionRepository = sessionRepository;
    }

    public long save(MemoryScope scope, String category, String key, String value, MemoryLayer layer) throws SQLException {
        validate(scope, key, value, layer);

        return switch (layer) {
            case WORKING -> {
                if (!scope.hasSessionId()) {
                    throw new IllegalArgumentException("WORKING layer requires sessionId");
                }
                yield workingRepo.save(scope.sessionId(), category, key, value, 1);
            }
            case LONG_TERM -> {
                if (!scope.hasProfileId()) {
                    throw new IllegalArgumentException("LONG_TERM layer requires profileId");
                }
                yield longTermRepo.save(scope.profileId(), category, key, value, 1);
            }
            default -> throw new IllegalArgumentException("Short-term memory is automatic (messages table)");
        };
    }

    public Optional<String> get(MemoryScope scope, String key) throws SQLException {
        if (scope.hasProfileId()) {
            var ltmOpt = longTermRepo.getByKey(scope.profileId(), key);
            if (ltmOpt.isPresent()) {
                return Optional.of(ltmOpt.get().value());
            }
        }

        if (scope.hasSessionId()) {
            return workingRepo.getByKey(scope.sessionId(), key)
                .map(WorkingMemoryDto::value);
        }

        return Optional.empty();
    }

    public Optional<String> get(long sessionId, long profileId, String key) throws SQLException {
        return get(new MemoryScope(sessionId, profileId), key);
    }

    public List<WorkingMemoryDto> getWorkingMemory(long sessionId) throws SQLException {
        return workingRepo.getBySession(sessionId);
    }

    public List<LongTermMemoryDto> getLongTermMemory(long profileId) throws SQLException {
        return longTermRepo.getByProfile(profileId);
    }

    public Map<String, Object> getAllMemory(long sessionId) throws SQLException {
        var session = sessionRepository.getSession(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        long profileId = session.profileId();

        Map<String, Object> result = new HashMap<>();
        result.put("working", getWorkingMemory(sessionId));
        result.put("longTerm", getLongTermMemory(profileId));

        return result;
    }

    public String buildMemoryContext(long sessionId) throws SQLException {
        return buildMemoryContext(sessionId, null);
    }

    public String buildMemoryContext(long sessionId, Set<String> requestedKeys) throws SQLException {
        var session = sessionRepository.getSession(sessionId)
            .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        long profileId = session.profileId();

        List<LongTermMemoryDto> ltmItems;
        if (requestedKeys == null || requestedKeys.isEmpty()) {
            ltmItems = longTermRepo.getByProfile(profileId);
        } else {
            ltmItems = longTermRepo.getByProfileAndKeys(profileId, requestedKeys);
        }

        Set<String> ltmKeys = ltmItems.stream()
            .map(LongTermMemoryDto::key)
            .collect(Collectors.toSet());

        List<WorkingMemoryDto> wmItems;
        if (requestedKeys == null || requestedKeys.isEmpty()) {
            wmItems = workingRepo.getBySession(sessionId);
        } else {
            wmItems = workingRepo.getBySessionAndKeys(sessionId, requestedKeys);
        }

        List<WorkingMemoryDto> filteredWmItems = wmItems.stream()
            .filter(item -> !ltmKeys.contains(item.key()))
            .toList();

        return formatMemoryContext(ltmItems, filteredWmItems);
    }

    private String formatMemoryContext(List<LongTermMemoryDto> ltmItems, List<WorkingMemoryDto> wmItems) {
        if (ltmItems.isEmpty() && wmItems.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder("Слои памяти:\n");

        if (!ltmItems.isEmpty()) {
            context.append("\n## Долговременная память:\n");
            String currentCategory = null;
            for (var item : ltmItems) {
                if (!item.category().equals(currentCategory)) {
                    currentCategory = item.category();
                    context.append("\n### ").append(currentCategory).append(":\n");
                }
                context.append("- ").append(item.key()).append(": ").append(item.value()).append("\n");
            }
        }

        if (!wmItems.isEmpty()) {
            context.append("\n## Рабочая память:\n");
            String currentCategory = null;
            for (var item : wmItems) {
                if (!item.category().equals(currentCategory)) {
                    currentCategory = item.category();
                    context.append("\n### ").append(currentCategory).append(":\n");
                }
                context.append("- ").append(item.key()).append(": ").append(item.value()).append("\n");
            }
        }

        return context.toString();
    }

    public void deleteFromMemory(MemoryScope scope, String key, MemoryLayer layer) throws SQLException {
        if (layer == MemoryLayer.WORKING && scope.hasSessionId()) {
            workingRepo.delete(scope.sessionId(), key);
        } else if (layer == MemoryLayer.LONG_TERM && scope.hasProfileId()) {
            longTermRepo.delete(scope.profileId(), key);
        } else {
            throw new IllegalArgumentException("Invalid scope for layer: " + layer);
        }
    }

    public void deleteWorkingMemory(long sessionId, String key) throws SQLException {
        workingRepo.delete(sessionId, key);
    }

    public void deleteLongTermMemory(long profileId, String key) throws SQLException {
        longTermRepo.delete(profileId, key);
    }

    private void validate(MemoryScope scope, String key, String value, MemoryLayer layer) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Key must be 1-" + MAX_KEY_LENGTH + " characters");
        }

        if (value == null || value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("Value too large (max " + MAX_VALUE_LENGTH + " chars)");
        }

        if (layer == MemoryLayer.LONG_TERM && !scope.hasProfileId()) {
            throw new IllegalArgumentException("LONG_TERM requires profileId");
        }

        if (layer == MemoryLayer.WORKING && !scope.hasSessionId()) {
            throw new IllegalArgumentException("WORKING requires sessionId");
        }
    }
}
