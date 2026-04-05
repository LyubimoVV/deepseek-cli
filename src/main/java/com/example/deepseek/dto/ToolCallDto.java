package com.example.deepseek.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolCallDto(
    @JsonProperty("id") String id,
    @JsonProperty("type") String type,
    @JsonProperty("function") FunctionDto function
) {
    public static ToolCallDto of(String id, String type, FunctionDto function) {
        return new ToolCallDto(id, type, function);
    }
}
