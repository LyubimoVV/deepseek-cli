package com.example.deepseek.invariant;

import com.example.deepseek.invariant.model.TechSpec;
import com.example.deepseek.invariant.parser.TechParser;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class TechDecisionInvariant extends Invariant {
    private final String description;
    private final List<String> decisionsRaw;
    private final List<TechSpec> decisionSpecs;
    
    public TechDecisionInvariant(String description, List<String> decisions) {
        this.description = description;
        this.decisionsRaw = decisions;
        this.decisionSpecs = decisions.stream()
            .map(TechParser::parse)
            .toList();
    }
    
    @Override
    public String getDescription() {
        return "Tech Decision: " + description;
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
            boolean isAllowed = decisionSpecs.stream()
                .anyMatch(d -> d.matches(mentionedSpec) || d.partialMatch(tech));
            
            if (!isAllowed) {
                violations.add(tech);
            }
        }
        
        return violations;
    }
    
    public List<TechSpec> getDecisionSpecs() {
        return decisionSpecs;
    }
    
    public List<String> getDecisionsRaw() {
        return decisionsRaw;
    }
    
    @Override
    public String getType() {
        return "TECH_DECISION";
    }
    
    @Override
    public String getConfig() {
        try {
            return objectMapper.writeValueAsString(decisionsRaw);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
