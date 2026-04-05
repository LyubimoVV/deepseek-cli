package com.example.deepseek.app.controllers;

import com.example.deepseek.dto.ProfileRequest;
import com.example.deepseek.memory.repository.ProfileRepository;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ProfileController {
    private static final Logger log = LoggerFactory.getLogger(ProfileController.class);
    
    private final AppContext ctx;
    
    public ProfileController(AppContext ctx) {
        this.ctx = ctx;
    }
    
    public void handleGetProfiles(Context ctx) {
        try {
            var profiles = this.ctx.getProfileRepository().getAll();
            ctx.json(Map.of("success", true, "profiles", profiles));
        } catch (Exception e) {
            log.error("Error getting profiles: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleCreateProfile(Context ctx) {
        try {
            var request = ctx.bodyAsClass(ProfileRequest.class);
            long id = this.ctx.getProfileRepository().create(request.name(), request.description(), request.systemPrompt(), request.personalization());
            ctx.json(Map.of("success", true, "profileId", id));
        } catch (Exception e) {
            log.error("Error creating profile: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleGetProfile(Context ctx) {
        try {
            long id = Long.parseLong(ctx.pathParam("id"));
            var profile = this.ctx.getProfileRepository().getById(id);
            if (profile.isPresent()) {
                ctx.json(Map.of("success", true, "profile", profile.get()));
            } else {
                ctx.status(404).json(Map.of("success", false, "error", "Profile not found"));
            }
        } catch (Exception e) {
            log.error("Error getting profile: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleUpdateProfile(Context ctx) {
        try {
            long id = Long.parseLong(ctx.pathParam("id"));
            var request = ctx.bodyAsClass(ProfileRequest.class);
            this.ctx.getProfileRepository().update(id, request.name(), request.description(), request.systemPrompt(), request.personalization());
            ctx.json(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error updating profile: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleDeleteProfile(Context ctx) {
        try {
            long id = Long.parseLong(ctx.pathParam("id"));
            var defaultProfile = this.ctx.getProfileRepository().getDefaultProfile();
            if (defaultProfile.isPresent() && defaultProfile.get().id() == id) {
                ctx.status(400).json(Map.of("success", false, "error", "Cannot delete default profile"));
                return;
            }
            this.ctx.getProfileRepository().delete(id);
            ctx.json(Map.of("success", true));
        } catch (Exception e) {
            log.error("Error deleting profile: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleGetDefaultProfile(Context ctx) {
        try {
            var profile = this.ctx.getProfileRepository().getDefaultProfile();
            if (profile.isPresent()) {
                ctx.json(Map.of("success", true, "profile", profile.get()));
            } else {
                ctx.status(404).json(Map.of("success", false, "error", "Default profile not found"));
            }
        } catch (Exception e) {
            log.error("Error getting default profile: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }

    public void handleSetSessionProfile(Context ctx) {
        try {
            long sessionId = Long.parseLong(ctx.pathParam("id"));
            long profileId = Long.parseLong(ctx.pathParam("profileId"));
            var profile = this.ctx.getProfileRepository().getById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found"));
            this.ctx.getSessionService().updateSessionProfile(sessionId, profileId, profile.systemPrompt());
            ctx.json(Map.of("success", true, "profileId", profileId));
        } catch (Exception e) {
            log.error("Error setting session profile: {}", e.getMessage());
            ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
        }
    }
}
