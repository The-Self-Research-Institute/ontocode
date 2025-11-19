package self.research.ontology.owlEditor.service.collaboration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.collaboration.EditOperation;
import self.research.ontology.owlEditor.model.collaboration.LockMessage;
import self.research.ontology.owlEditor.model.collaboration.PresenceMessage;
import self.research.ontology.owlEditor.websocket.WebSocketEventListener;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Core service for managing collaborative editing sessions.
 * Handles edit operations, conflict resolution, and broadcasting.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollaborativeEditService {

    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketEventListener eventListener;
    
    // Operation history per project: projectId -> Queue<EditOperation>
    private final Map<String, Queue<EditOperation>> operationHistory = new ConcurrentHashMap<>();
    
    // Active locks per project: projectId -> Map<nodeId, LockInfo>
    private final Map<String, Map<String, LockInfo>> projectLocks = new ConcurrentHashMap<>();
    
    // User colors: userId -> color (hex)
    private final Map<String, String> userColors = new ConcurrentHashMap<>();
    
    private static final String[] COLORS = {
        "#FF6B6B", "#4ECDC4", "#45B7D1", "#FFA07A", "#98D8C8",
        "#F7DC6F", "#BB8FCE", "#85C1E2", "#F8B739", "#52B788"
    };
    
    private int colorIndex = 0;

    /**
     * Process an edit operation from a client.
     * Validates, applies server timestamp, and broadcasts to all clients.
     */
    public EditOperation processEdit(EditOperation operation) {
        // Add server timestamp
        operation.setServerTimestamp(System.currentTimeMillis());
        
        // Validate the operation
        if (!validateOperation(operation)) {
            log.warn("Invalid operation rejected: {}", operation);
            return null;
        }
        
        // Check if node is locked by another user
        if (isLockedByOther(operation.getProjectId(), operation.getNodeId(), operation.getUserId())) {
            log.warn("Edit rejected - node {} locked by another user", operation.getNodeId());
            sendErrorToUser(operation.getUserId(), operation.getProjectId(), 
                "Cannot edit: node is locked by another user");
            return null;
        }
        
        // Add to operation history
        addToHistory(operation);
        
        // Broadcast to all clients in the project
        broadcastEdit(operation);
        
        log.info("Processed edit: type={}, node={}, user={}", 
                operation.getType(), operation.getNodeId(), operation.getUserId());
        
        return operation;
    }

    /**
     * Process a presence update (user joined, cursor moved, etc.).
     */
    public void processPresence(PresenceMessage message) {
        // Assign color to new users
        if (message.getType() == PresenceMessage.PresenceType.USER_JOINED) {
            String color = assignColor(message.getUserId());
            message.setColor(color);
            
            // Register session
            eventListener.registerSession(
                message.getSessionId(), 
                message.getProjectId(), 
                message.getUserId(), 
                message.getUsername()
            );
        }
        
        message.setTimestamp(System.currentTimeMillis());
        
        // Broadcast presence update
        messagingTemplate.convertAndSend(
            "/topic/presence/" + message.getProjectId(),
            message
        );
        
        log.debug("Processed presence: type={}, user={}, project={}", 
                message.getType(), message.getUserId(), message.getProjectId());
    }

    /**
     * Acquire a lock on a node for editing.
     */
    public LockMessage acquireLock(String projectId, String nodeId, String userId, 
                                   String username, String sessionId) {
        Map<String, LockInfo> locks = projectLocks.computeIfAbsent(projectId, k -> new ConcurrentHashMap<>());
        
        LockInfo existingLock = locks.get(nodeId);
        
        // Check if already locked
        if (existingLock != null && !existingLock.getUserId().equals(userId)) {
            // Check if lock expired
            if (existingLock.getExpiresAt() < System.currentTimeMillis()) {
                // Lock expired, release it
                locks.remove(nodeId);
                log.info("Expired lock removed for node {}", nodeId);
            } else {
                // Lock still valid, deny request
                return LockMessage.builder()
                        .type(LockMessage.LockType.LOCK_DENIED)
                        .projectId(projectId)
                        .nodeId(nodeId)
                        .userId(userId)
                        .username(username)
                        .sessionId(sessionId)
                        .success(false)
                        .error("Node is locked by " + existingLock.getUsername())
                        .timestamp(System.currentTimeMillis())
                        .build();
            }
        }
        
        // Acquire lock (30 second timeout)
        long expiresAt = System.currentTimeMillis() + 30000;
        LockInfo lock = new LockInfo(userId, username, sessionId, expiresAt);
        locks.put(nodeId, lock);
        
        LockMessage message = LockMessage.builder()
                .type(LockMessage.LockType.LOCK_ACQUIRED)
                .projectId(projectId)
                .nodeId(nodeId)
                .userId(userId)
                .username(username)
                .sessionId(sessionId)
                .expiresAt(expiresAt)
                .success(true)
                .timestamp(System.currentTimeMillis())
                .build();
        
        // Broadcast lock acquisition
        messagingTemplate.convertAndSend("/topic/locks/" + projectId, message);
        
        log.info("Lock acquired: node={}, user={}, project={}", nodeId, username, projectId);
        
        return message;
    }

    /**
     * Release a lock on a node.
     */
    public void releaseLock(String projectId, String nodeId, String userId, String sessionId) {
        Map<String, LockInfo> locks = projectLocks.get(projectId);
        if (locks == null) return;
        
        LockInfo lock = locks.get(nodeId);
        if (lock != null && lock.getUserId().equals(userId)) {
            locks.remove(nodeId);
            
            LockMessage message = LockMessage.builder()
                    .type(LockMessage.LockType.LOCK_RELEASED)
                    .projectId(projectId)
                    .nodeId(nodeId)
                    .userId(userId)
                    .username(lock.getUsername())
                    .sessionId(sessionId)
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            messagingTemplate.convertAndSend("/topic/locks/" + projectId, message);
            
            log.info("Lock released: node={}, user={}, project={}", nodeId, lock.getUsername(), projectId);
        }
    }

    /**
     * Release all locks held by a user (on disconnect).
     */
    public void releaseUserLocks(String projectId, String sessionId) {
        Map<String, LockInfo> locks = projectLocks.get(projectId);
        if (locks == null) return;
        
        List<String> toRelease = new ArrayList<>();
        locks.forEach((nodeId, lock) -> {
            if (lock.getSessionId().equals(sessionId)) {
                toRelease.add(nodeId);
            }
        });
        
        for (String nodeId : toRelease) {
            LockInfo lock = locks.remove(nodeId);
            if (lock != null) {
                LockMessage message = LockMessage.builder()
                        .type(LockMessage.LockType.LOCK_EXPIRED)
                        .projectId(projectId)
                        .nodeId(nodeId)
                        .userId(lock.getUserId())
                        .username(lock.getUsername())
                        .sessionId(sessionId)
                        .timestamp(System.currentTimeMillis())
                        .build();
                
                messagingTemplate.convertAndSend("/topic/locks/" + projectId, message);
            }
        }
        
        log.info("Released {} locks for session {}", toRelease.size(), sessionId);
    }

    /**
     * Get operation history for a project.
     */
    public List<EditOperation> getHistory(String projectId, int limit) {
        Queue<EditOperation> history = operationHistory.get(projectId);
        if (history == null) return Collections.emptyList();
        
        return history.stream()
                .skip(Math.max(0, history.size() - limit))
                .toList();
    }

    /**
     * Get active users in a project.
     */
    public List<Map<String, Object>> getActiveUsers(String projectId) {
        Map<String, WebSocketEventListener.UserSession> sessions = eventListener.getProjectSessions(projectId);
        
        List<Map<String, Object>> result = new ArrayList<>();
        for (WebSocketEventListener.UserSession session : sessions.values()) {
            Map<String, Object> userMap = new HashMap<>();
            userMap.put("userId", session.getUserId());
            userMap.put("username", session.getUsername());
            userMap.put("sessionId", session.getSessionId());
            userMap.put("color", userColors.getOrDefault(session.getUserId(), "#999999"));
            userMap.put("lastActivity", session.getLastActivity());
            result.add(userMap);
        }
        return result;
    }

    // Private helper methods

    private boolean validateOperation(EditOperation operation) {
        return operation.getProjectId() != null &&
               operation.getNodeId() != null &&
               operation.getType() != null &&
               operation.getUserId() != null;
    }

    private boolean isLockedByOther(String projectId, String nodeId, String userId) {
        Map<String, LockInfo> locks = projectLocks.get(projectId);
        if (locks == null) return false;
        
        LockInfo lock = locks.get(nodeId);
        if (lock == null) return false;
        
        // Check if locked by another user and not expired
        return !lock.getUserId().equals(userId) && 
               lock.getExpiresAt() > System.currentTimeMillis();
    }

    private void addToHistory(EditOperation operation) {
        Queue<EditOperation> history = operationHistory.computeIfAbsent(
            operation.getProjectId(), 
            k -> new ConcurrentLinkedQueue<>()
        );
        
        history.offer(operation);
        
        // Keep last 1000 operations
        while (history.size() > 1000) {
            history.poll();
        }
    }

    private void broadcastEdit(EditOperation operation) {
        messagingTemplate.convertAndSend(
            "/topic/ontology/" + operation.getProjectId(),
            operation
        );
    }

    private void sendErrorToUser(String userId, String projectId, String error) {
        Map<String, Object> errorMessage = Map.of(
            "type", "ERROR",
            "message", error,
            "timestamp", System.currentTimeMillis()
        );
        
        messagingTemplate.convertAndSendToUser(
            userId, 
            "/queue/ontology/" + projectId, 
            errorMessage
        );
    }

    private String assignColor(String userId) {
        return userColors.computeIfAbsent(userId, k -> {
            String color = COLORS[colorIndex % COLORS.length];
            colorIndex++;
            return color;
        });
    }

    // Inner class for lock information
    private static class LockInfo {
        private final String userId;
        private final String username;
        private final String sessionId;
        private final long expiresAt;

        public LockInfo(String userId, String username, String sessionId, long expiresAt) {
            this.userId = userId;
            this.username = username;
            this.sessionId = sessionId;
            this.expiresAt = expiresAt;
        }

        public String getUserId() { return userId; }
        public String getUsername() { return username; }
        public String getSessionId() { return sessionId; }
        public long getExpiresAt() { return expiresAt; }
    }
}
