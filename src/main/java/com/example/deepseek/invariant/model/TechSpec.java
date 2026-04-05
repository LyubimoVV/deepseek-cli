package com.example.deepseek.invariant.model;

public record TechSpec(
    String name,
    String version
) {
    public boolean matches(TechSpec other) {
        if (!this.name.equalsIgnoreCase(other.name)) {
            return false;
        }
        if (this.version == null || other.version == null) {
            return true;
        }
        return this.version.equals(other.version);
    }
    
    public boolean partialMatch(String text) {
        String lower = text.toLowerCase();
        String lowerName = name.toLowerCase();
        return lower.contains(lowerName) || lowerName.contains(lower);
    }
    
    public String displayName() {
        return version != null ? name + " " + version : name;
    }
}
