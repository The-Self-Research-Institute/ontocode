package self.research.ontology.owlEditor.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages WebSocket session lifecycle events.
 * Tracks active users per project and handles connect/disconnect events.
 */
@Slf4j
@Component
public class WebSocketEventListener {

    private final SimpMessagingTemplate messagingTemplate;
    
    // Track sessions: sessionId -> projectId
    private final Map<String, String> sessionProjects = new ConcurrentHashMap<>();
    
    // Track active users per project: projectId -> Set<sessionId>
    private final Map<String, Map<String, UserSession>> projectSessions = new ConcurrentHashMap<>();

    public WebSocketEventListener(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        
        log.info("WebSocket connection established: sessionId={}", sessionId);
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        
        // Get the project this session was working on
        String projectId = sessionProjects.remove(sessionId);
        
        if (projectId != null) {
            // Remove user from project sessions
            Map<String, UserSession> sessions = projectSessions.get(projectId);
            if (sessions != null) {
                UserSession userSession = sessions.remove(sessionId);
                
                if (userSession != null) {
                    // Broadcast user left message
                    Map<String, Object> message = Map.of(
                        "type", "USER_LEFT",
                        "userId", userSession.getUserId(),
                        "sessionId", sessionId,
                        "timestamp", System.currentTimeMillis()
                    );
                    
                    messagingTemplate.convertAndSend(
                        "/topic/presence/" + projectId, 
                        message
                    );
                    
                    log.info("User {} disconnected from project {}", 
                            userSession.getUserId(), projectId);
                }
                
                // Clean up empty project sessions
                if (sessions.isEmpty()) {
                    projectSessions.remove(projectId);
                }
            }
        }
        
        log.info("WebSocket connection closed: sessionId={}", sessionId);
    }

    /**
     * Register a user session for a project.
     */
    public void registerSession(String sessionId, String projectId, String userId, String username) {
        sessionProjects.put(sessionId, projectId);
        
        projectSessions.computeIfAbsent(projectId, k -> new ConcurrentHashMap<>())
                .put(sessionId, new UserSession(userId, username, sessionId));
        
        log.info("Registered session {} for user {} in project {}", sessionId, username, projectId);
    }

    /**
     * Get active sessions for a project.
     */
    public Map<String, UserSession> getProjectSessions(String projectId) {
        return projectSessions.getOrDefault(projectId, Map.of());
    }

    /**
     * Get session count for a project.
     */
    public int getSessionCount(String projectId) {
        Map<String, UserSession> sessions = projectSessions.get(projectId);
        return sessions != null ? sessions.size() : 0;
    }

    /**
     * Get all active sessions across all projects — used by admin active-users view.
     * Returns list of maps: { userId, username, sessionId, projectId, lastActivity }
     */
    public java.util.List<java.util.Map<String, Object>> getAllActiveSessions() {
        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        projectSessions.forEach((projectId, sessions) ->
            sessions.forEach((sessionId, ws) -> {
                java.util.Map<String, Object> entry = new java.util.HashMap<>();
                entry.put("userId", ws.getUserId());
                entry.put("username", ws.getUsername());
                entry.put("sessionId", sessionId);
                entry.put("projectId", projectId);
                entry.put("lastActivity", ws.getLastActivity());
                result.add(entry);
            })
        );
        return result;
    }

    /** Total number of live WebSocket connections. */
    public int getTotalConnectionCount() {
        return sessionProjects.size();
    }

    /**
     * Inner class to represent a user session.
     */
    public static class UserSession {
        private final String userId;
        private final String username;
        private final String sessionId;
        private long lastActivity;

        public UserSession(String userId, String username, String sessionId) {
            this.userId = userId;
            this.username = username;
            this.sessionId = sessionId;
            this.lastActivity = System.currentTimeMillis();
        }

        public String getUserId() {
            return userId;
        }

        public String getUsername() {
            return username;
        }

        public String getSessionId() {
            return sessionId;
        }

        public long getLastActivity() {
            return lastActivity;
        }

        public void updateActivity() {
            this.lastActivity = System.currentTimeMillis();
        }
    }
}
