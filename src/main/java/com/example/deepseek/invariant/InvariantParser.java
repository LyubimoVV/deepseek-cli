package com.example.deepseek.invariant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class InvariantParser {
    private static final Logger log = LoggerFactory.getLogger(InvariantParser.class);
    
    public static List<Invariant> parse(String content) {
        List<Invariant> invariants = new ArrayList<>();
        
        if (content == null || content.isBlank()) {
            return invariants;
        }
        
        String[] lines = content.split("\n");
        String currentType = null;
        List<String> currentItems = new ArrayList<>();
        
        for (String line : lines) {
            line = line.trim();
            
            if (line.startsWith("## ")) {
                if (currentType != null && !currentItems.isEmpty()) {
                    invariants.add(createInvariant(currentType, currentItems));
                }
                
                currentType = line.substring(3).trim().toUpperCase();
                currentItems = new ArrayList<>();
            } else if (line.startsWith("- ") && currentType != null) {
                String item = line.substring(2).trim();
                if (!item.isEmpty()) {
                    currentItems.add(item);
                }
            }
        }
        
        if (currentType != null && !currentItems.isEmpty()) {
            invariants.add(createInvariant(currentType, currentItems));
        }
        
        log.debug("Parsed {} invariants from markdown", invariants.size());
        return invariants;
    }
    
    private static Invariant createInvariant(String type, List<String> items) {
        String description = String.join(", ", items);
        String configJson = toJsonArray(items);
        
        return switch (type) {
            case "STACK" -> new StackOnlyInvariant(description, items);
            case "ARCHITECTURE" -> new ArchitectureInvariant(description, items);
            case "TECH_DECISION" -> new TechDecisionInvariant(description, items);
            case "BUSINESS_RULE" -> new BusinessRuleInvariant(description, String.join("; ", items));
            default -> {
                log.warn("Unknown invariant type: {}, treating as business rule", type);
                yield new BusinessRuleInvariant(description, String.join("; ", items));
            }
        };
    }
    
    private static String toJsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(items.get(i).replace("\"", "\\\"")).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }
}
