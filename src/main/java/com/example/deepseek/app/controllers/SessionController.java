package com.example.deepseek.app.controllers;

import com.example.deepseek.db.MessageDto;
import com.example.deepseek.db.SessionDto;
import com.example.deepseek.db.SessionService;
import com.example.deepseek.memory.dto.ProfileDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class SessionController {
    private static final Logger log = LoggerFactory.getLogger(SessionController.class);
    
    private final AppContext appCtx;
    
    public SessionController(AppContext ctx) {
        this.appCtx = ctx;
    }
    
    public void handleGetSessions(Context ctx) {
        log.info("Get sessions");
        List<SessionDto> sessions = this.appCtx.getSessionService().getAllSessions();
        ctx.json(Map.of("success", true, "sessions", sessions));
    }

    public void handleCreateSession(Context ctx) throws Exception {
        Map<String, String> request = ctx.bodyAsClass(Map.class);
        String title = request.get("title");

        log.info("Create session: title={}", title);

        long oldSessionId = this.appCtx.getSessionService().getCurrentSessionId();
        long profileId = this.appCtx.getProfileIdForSession(oldSessionId);

        String systemMessage = getSystemMessageFromProfile(profileId);

        long sessionId = this.appCtx.getSessionService().createSession(
            title != null ? title : "Новая сессия",
            this.appCtx.getClientManager().getCurrentModel(),
            systemMessage,
            2,
            profileId
        );

        this.appCtx.getClientManager().clearAllHistory();

        SessionDto session = this.appCtx.getSessionService().getSession(sessionId).orElseThrow();
        log.info("Create session: success, session_id={}", sessionId);
        ctx.json(Map.of("success", true, "session", session));
    }

    public void handleGetSession(Context ctx) {
        long id = Long.parseLong(ctx.pathParam("id"));
        log.info("Get session: id={}", id);
        var session = this.appCtx.getSessionService().getSession(id);

        if (session.isPresent()) {
            ctx.json(Map.of("success", true, "session", session.get()));
        } else {
            ctx.status(404).json(Map.of("success", false, "error", "Сессия не найдена"));
        }
    }

    public void handleDeleteSession(Context ctx) {
        long id = Long.parseLong(ctx.pathParam("id"));
        long currentId = this.appCtx.getSessionService().getCurrentSessionId();
        log.info("Delete session: id={}, current_session_id={}", id, currentId);

        long profileId = this.appCtx.getProfileIdForSession(id);

        this.appCtx.getSessionService().deleteSession(id);

        if (id == currentId) {
            var sessions = this.appCtx.getSessionService().getAllSessions();
            if (!sessions.isEmpty()) {
                SessionDto firstSession = sessions.get(0);
                this.appCtx.getSessionService().setActiveSession(firstSession.id());
                this.appCtx.getSessionService().restoreSessionToClient(this.appCtx.getClientManager(), this.appCtx.getSummaryAgent());
                log.info("Delete session: switched to session_id={}", firstSession.id());
            } else {
                String systemMessage = getSystemMessageFromProfile(profileId);
                long newSessionId = this.appCtx.getSessionService().createSession(
                    "Новая сессия",
                    this.appCtx.getClientManager().getCurrentModel(),
                    systemMessage,
                    2,
                    profileId
                );
                this.appCtx.getClientManager().clearAllHistory();
                log.info("Delete session: created new session_id={}", newSessionId);
            }
        }

        ctx.json(Map.of("success", true, "message", "Сессия удалена"));
    }

    public void handleGetSessionMessages(Context ctx) {
        long id = Long.parseLong(ctx.pathParam("id"));
        log.info("Get session messages: session_id={}", id);
        List<MessageDto> messages = this.appCtx.getSessionService().getSessionMessages(id);
        ctx.json(Map.of("success", true, "messages", messages));
    }

    public void handleActivateSession(Context ctx) throws Exception {
        long id = Long.parseLong(ctx.pathParam("id"));
        log.info("Activate session: id={}", id);
        
        var sessionOpt = this.appCtx.getSessionService().getSession(id);

        if (sessionOpt.isEmpty()) {
            ctx.status(404).json(Map.of("success", false, "error", "Сессия не найдена"));
            return;
        }

        SessionDto session = sessionOpt.get();
        this.appCtx.getSessionService().setActiveSession(id);

        this.appCtx.getClientManager().clearAllHistory();
        this.appCtx.getClientManager().setCurrentModel(session.model() != null ? session.model() : this.appCtx.getClientManager().getCurrentModel());
        this.appCtx.getClientManager().setMode(session.mode());
        if (session.systemMessage() != null) {
            this.appCtx.getClientManager().setSystemMessage(session.systemMessage());
        }

        this.appCtx.getSessionService().restoreSessionToClient(this.appCtx.getClientManager(), this.appCtx.getSummaryAgent());

        log.info("Activate session: success, session_id={}, title={}", session.id(), session.title());
        ctx.json(Map.of("success", true, "session", session, "message", "Сессия активирована"));
    }

    public void handleGetActiveSession(Context ctx) {
        log.info("Get active session");
        var session = this.appCtx.getSessionService().getActiveSession();
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("session", session.orElse(null));
        ctx.json(response);
    }

    public void handleGetSessionStats(Context ctx) {
        long id = Long.parseLong(ctx.pathParam("id"));
        log.info("Get session stats: session_id={}", id);
        
        var stats = this.appCtx.getSessionService().getSessionStats(id);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("stats", Map.of(
            "totalTokens", stats.totalTokens(),
            "totalCost", stats.totalCost(),
            "requestCount", stats.requestCount()
        ));
        ctx.json(response);
    }

    private String getSystemMessageFromProfile(long profileId) {
        try {
            var profile = this.appCtx.getProfileRepository().getById(profileId);
            if (profile.isPresent()) {
                ProfileDto p = profile.get();
                String systemPrompt = p.systemPrompt() != null && !p.systemPrompt().isBlank() 
                    ? p.systemPrompt() 
                    : this.appCtx.getClientManager().getSystemMessage();
                return applyPersonalization(systemPrompt, p.personalization());
            }
            return this.appCtx.getClientManager().getSystemMessage();
        } catch (Exception e) {
            log.warn("Failed to get system message from profile {}: {}", profileId, e.getMessage());
            return this.appCtx.getClientManager().getSystemMessage();
        }
    }

    private String applyPersonalization(String systemPrompt, String personalization) {
        if (personalization == null || personalization.isBlank()) {
            return systemPrompt;
        }
        try {
            Map<String, Object> map = new ObjectMapper().readValue(personalization, Map.class);
            StringBuilder sb = new StringBuilder(systemPrompt);
            sb.append("\n\nПерсонализация:");
            for (Map.Entry<String, Object> e : map.entrySet()) {
                sb.append("\n").append(e.getKey()).append(": ").append(e.getValue());
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Failed to parse personalization: {}", e.getMessage());
            return systemPrompt;
        }
    }
}
