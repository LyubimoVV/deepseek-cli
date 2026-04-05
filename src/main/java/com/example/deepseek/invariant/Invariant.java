package com.example.deepseek.invariant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public abstract class Invariant {
    protected static final ObjectMapper objectMapper = new ObjectMapper();
    
    public abstract String getDescription();
    public abstract boolean check(String response);
    public abstract String getType();
    public abstract String getConfig();
    
    public static Invariant fromConfig(String type, String description, String config) {
        return switch (type) {
            case "STACK" -> new StackOnlyInvariant(description, parseList(config));
            case "ARCHITECTURE" -> new ArchitectureInvariant(description, parseList(config));
            case "TECH_DECISION" -> new TechDecisionInvariant(description, parseList(config));
            case "BUSINESS_RULE" -> new BusinessRuleInvariant(description, config);
            default -> throw new IllegalArgumentException("Unknown invariant type: " + type);
        };
    }
    
    protected static List<String> parseList(String config) {
        try {
            return objectMapper.readValue(config, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
