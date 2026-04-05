package com.example.deepseek.app.controllers;

import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class TestReviewController {
    private static final Logger log = LoggerFactory.getLogger(TestReviewController.class);
    
    private final AppContext ctx;
    
    public TestReviewController(AppContext ctx) {
        this.ctx = ctx;
    }
    
    public void handleHealth(Context ctx) {
        log.info("Test review health check");
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("message", "Test review API is working");
        response.put("timestamp", LocalDateTime.now().toString());
        response.put("branch", "test_review");
        response.put("purpose", "Testing git workflows");
        
        ctx.json(Map.of("success", true, "data", response));
    }
    
    public void handleInfo(Context ctx) {
        log.info("Test review info requested");
        
        Map<String, Object> info = new HashMap<>();
        info.put("api_version", "1.0.0");
        info.put("description", "Test API for git workflow verification");
        info.put("features", new String[]{
            "Health check endpoint",
            "Info endpoint",
            "Test data generation"
        });
        info.put("created_for", "PR testing purposes");
        
        ctx.json(Map.of("success", true, "info", info));
    }
    
    public void handleTestData(Context ctx) {
        log.info("Generating test data");
        
        Map<String, Object> testData = new HashMap<>();
        testData.put("id", 1);
        testData.put("name", "Test Item");
        testData.put("value", 42);
        testData.put("active", true);
        
        ctx.json(Map.of("success", true, "test_data", testData));
    }
}
