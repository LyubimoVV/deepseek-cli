package com.example.deepseek.invariant;

import java.util.Set;
import java.util.stream.Collectors;

public class TechnologyRegistry {
    
    private static final Set<String> KNOWN_TECH = Set.of(
        "spring", "spring boot", "springboot",
        "django", "flask", "rails", "laravel",
        "express", "express.js", "expressjs",
        "fastapi", "javalin", "ktor", "quarkus", "micronaut",
        "nest", "nestjs", "nest.js",
        "asp.net", "aspnet",
        "java", "kotlin", "python", "javascript", "typescript",
        "ruby", "php", "go", "golang", "scala", "rust", "c#", "csharp",
        "sqlite", "postgresql", "postgres", "mysql", "mongodb", "mongo", "redis",
        "oracle", "sql server", "sqlserver", "cassandra",
        "jackson", "gson", "moshi",
        "hibernate", "mybatis", "jooq",
        "react", "angular", "vue", "vue.js", "svelte",
        "mvc", "mvvm", "monolith", "microservice", "microservices",
        "clean architecture", "hexagonal", "solid"
    );
    
    public static Set<String> extract(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        
        String lower = text.toLowerCase();
        return KNOWN_TECH.stream()
            .filter(lower::contains)
            .collect(Collectors.toSet());
    }
    
    public static Set<String> getKnownTech() {
        return KNOWN_TECH;
    }
}
