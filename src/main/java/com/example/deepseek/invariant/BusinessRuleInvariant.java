package com.example.deepseek.invariant;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BusinessRuleInvariant extends Invariant {
    private final String description;
    private final String rule;
    private final List<String> allowedKeywords;
    
    public BusinessRuleInvariant(String description, String rule) {
        this.description = description;
        this.rule = rule;
        this.allowedKeywords = parseAllowedKeywords(rule);
    }
    
    private List<String> parseAllowedKeywords(String rule) {
        if (rule == null || rule.isBlank()) {
            return List.of();
        }
        return List.of(rule.toLowerCase().split("[,;\\s]+"));
    }
    
    @Override
    public String getDescription() {
        return "Business Rule: " + description;
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
        
        Set<String> forbiddenKeywords = Set.of(
            "exploit", "injection", "xss", "csrf", "rce",
            "bypass", "hack", "crack", "vulnerability"
        );
        
        List<String> violations = new ArrayList<>();
        
        for (String tech : mentioned) {
            if (forbiddenKeywords.contains(tech.toLowerCase())) {
                violations.add(tech);
            }
        }
        
        String lower = response.toLowerCase();
        if (lower.contains("обход") || lower.contains("взломать") || 
            lower.contains("уязвимость") || lower.contains("инъекци")) {
            if (!violations.contains("security-violation")) {
                violations.add("security-violation");
            }
        }
        
        return violations;
    }
    
    @Override
    public String getType() {
        return "BUSINESS_RULE";
    }
    
    @Override
    public String getConfig() {
        return rule;
    }
}
