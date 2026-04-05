package com.example.deepseek.invariant;

import com.example.deepseek.invariant.model.TechSpec;
import com.example.deepseek.invariant.parser.TechParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class InvariantService {
    private static final Logger log = LoggerFactory.getLogger(InvariantService.class);
    
    private static final String INVARIANTS_PATH_ENV = "APP_INVARIANTS_PATH";
    private static final String DEFAULT_INVARIANTS_PATH = "INVARIANTS.md";
    
    private final Path invariantsPath;
    
    public InvariantService() {
        String customPath = System.getenv(INVARIANTS_PATH_ENV);
        this.invariantsPath = customPath != null && !customPath.isBlank()
            ? Path.of(customPath)
            : Path.of(DEFAULT_INVARIANTS_PATH);
        
        log.info("InvariantService initialized with path: {}", invariantsPath.toAbsolutePath());
    }
    
    public List<Invariant> getAllInvariants() {
        try {
            if (!Files.exists(invariantsPath)) {
                log.debug("Invariants file not found: {}", invariantsPath);
                return new ArrayList<>();
            }
            
            String content = Files.readString(invariantsPath);
            return InvariantParser.parse(content);
        } catch (IOException e) {
            log.error("Failed to read invariants file: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    
    public String buildInvariantsSection() {
        List<Invariant> invariants = getAllInvariants();
        
        if (invariants.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("[INVARIANTS]\n");

        for (Invariant inv : invariants) {
            sb.append("- ").append(inv.getDescription()).append("\n");
        }

        sb.append("Нарушение любого инварианта ЗАПРЕЩЕНО.\n\n");

        sb.append("\nЕсли условия задачи пользователя нарушает инвариант - откажитесь и предложите альтернативу.\n");
        
        return sb.toString();
    }
    
    public boolean hasInvariants() {
        return !getAllInvariants().isEmpty();
    }
    
    public Path getInvariantsPath() {
        return invariantsPath;
    }
    
    public ValidationResult validateUserRequest(String query) {
        if (query == null || query.isBlank()) {
            return new ValidationResult.Pass(query);
        }
        
        Set<String> mentioned = TechnologyRegistry.extract(query);
        
        if (mentioned.isEmpty()) {
            return new ValidationResult.Pass(query);
        }
        
        List<TechSpec> allowedSpecs = getAllowedTechSpecs();
        List<String> allowedNames = getAllowedTechNames();
        List<String> violations = new ArrayList<>();
        
        for (String tech : mentioned) {
            TechSpec spec = TechParser.parse(tech);
            boolean isAllowed = allowedSpecs.stream()
                .anyMatch(allowed -> allowed.matches(spec) || allowed.partialMatch(tech));
            
            if (!isAllowed) {
                violations.add(tech);
            }
        }
        
        if (violations.isEmpty()) {
            return new ValidationResult.Pass(query);
        }
        
        return new ValidationResult.UserRequestViolation(
            violations,
            allowedNames,
            "Запрос содержит неразрешённые технологии"
        );
    }
    
    private List<TechSpec> getAllowedTechSpecs() {
        List<TechSpec> specs = new ArrayList<>();
        
        for (Invariant inv : getAllInvariants()) {
            if (inv instanceof StackOnlyInvariant stack) {
                specs.addAll(stack.getAllowedSpecs());
            } else if (inv instanceof TechDecisionInvariant tech) {
                specs.addAll(tech.getDecisionSpecs());
            } else if (inv instanceof ArchitectureInvariant arch) {
                specs.addAll(arch.getAllowedSpecs());
            }
        }
        
        return specs;
    }
    
    private List<String> getAllowedTechNames() {
        List<String> names = new ArrayList<>();
        
        for (Invariant inv : getAllInvariants()) {
            if (inv instanceof StackOnlyInvariant stack) {
                names.addAll(stack.getAllowedRaw());
            } else if (inv instanceof TechDecisionInvariant tech) {
                names.addAll(tech.getDecisionsRaw());
            } else if (inv instanceof ArchitectureInvariant arch) {
                names.addAll(arch.getAllowedRaw());
            }
        }
        
        return names;
    }
}
