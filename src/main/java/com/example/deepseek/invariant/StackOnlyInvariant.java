package com.example.deepseek.invariant;

import com.example.deepseek.invariant.model.TechSpec;
import com.example.deepseek.invariant.parser.TechParser;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class StackOnlyInvariant extends Invariant {
    private final String description;
    private final List<String> allowedRaw;
    private final List<TechSpec> allowedSpecs;
    
    public StackOnlyInvariant(String description, List<String> allowed) {
        this.description = description;
        this.allowedRaw = allowed;
        this.allowedSpecs = allowed.stream()
            .map(TechParser::parse)
            .toList();
    }
    
    @Override
    public String getDescription() {
        return "Stack: " + description;
    }
    
    @Override
    public boolean check(String response) {
        return findViolations(response).isEmpty();
    }
    
    public List<String> findViolations(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        
        Set<String> mentioned = TechnologyRegistry.extract(response);
        List<String> violations = new ArrayList<>();
        
        for (String tech : mentioned) {
            TechSpec mentionedSpec = TechParser.parse(tech);
            boolean isAllowed = allowedSpecs.stream()
                .anyMatch(allowed -> allowed.matches(mentionedSpec) || allowed.partialMatch(tech));
            
            if (!isAllowed) {
                violations.add(tech);
            }
        }
        
        return violations;
    }
    
    public List<TechSpec> getAllowedSpecs() {
        return allowedSpecs;
    }
    
    public List<String> getAllowedRaw() {
        return allowedRaw;
    }
    
    @Override
    public String getType() {
        return "STACK";
    }
    
    @Override
    public String getConfig() {
        try {
            return objectMapper.writeValueAsString(allowedRaw);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
