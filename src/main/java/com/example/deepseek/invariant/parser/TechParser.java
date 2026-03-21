package com.example.deepseek.invariant.parser;

import com.example.deepseek.invariant.model.TechSpec;

public class TechParser {
    
    public static TechSpec parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new TechSpec("", null);
        }
        
        String[] parts = raw.trim().split("\\s+", 2);
        String name = parts[0];
        String version = null;
        
        if (parts.length > 1 && parts[1].matches("\\d+.*")) {
            version = parts[1];
        }
        
        return new TechSpec(name, version);
    }
}
