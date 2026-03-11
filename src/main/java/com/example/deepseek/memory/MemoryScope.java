package com.example.deepseek.memory;

public record MemoryScope(Long sessionId, Long profileId) {

    public static MemoryScope ofSession(long sessionId) {
        return new MemoryScope(sessionId, null);
    }

    public static MemoryScope ofProfile(long profileId) {
        return new MemoryScope(null, profileId);
    }

    public static MemoryScope ofBoth(long sessionId, long profileId) {
        return new MemoryScope(sessionId, profileId);
    }

    public boolean hasSessionId() {
        return sessionId != null && sessionId > 0;
    }

    public boolean hasProfileId() {
        return profileId != null && profileId > 0;
    }
}
