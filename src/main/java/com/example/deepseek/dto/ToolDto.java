package com.example.deepseek.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDto(
    @JsonProperty("type") String type,
    @JsonProperty("function") FunctionDto function
) {
    public static ToolDto function(String name, String description, Map<String, Object> parameters) {
        return new ToolDto("function", FunctionDto.forDefinition(name, description, parameters));
    }
}
