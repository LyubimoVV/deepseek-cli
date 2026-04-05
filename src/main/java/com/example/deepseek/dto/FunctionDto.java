package com.example.deepseek.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FunctionDto(
    String name,
    String description,
    @JsonProperty("arguments") String argumentsJson,
    Map<String, Object> parameters
) {
    public static FunctionDto forCall(String name, String argumentsJson) {
        return new FunctionDto(name, null, argumentsJson, null);
    }
    
    public static FunctionDto forDefinition(String name, String description, Map<String, Object> parameters) {
        return new FunctionDto(name, description, null, parameters);
    }
}
