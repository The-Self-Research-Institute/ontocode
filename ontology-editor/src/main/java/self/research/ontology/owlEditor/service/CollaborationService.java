package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for real-time collaboration features.
 * Tracks active users, detects conflicts, and manages concurrent editing.
 */
@Service
public class CollaborationService {

    private static final Logger log = LoggerFactory.getLogger(CollaborationService.class);

    // Active sessions: projectId -> Set of ActiveUsers
    private final Map<String, Set<ActiveUser>> activeSessions = new ConcurrentHashMap<>();
    
    // Entity locks: entityIRI -> LockInfo
    private final Map<String, LockInfo> entityLocks = new ConcurrentHashMap<>();
    
    // Recent activity: projectId -> List of Activities
    private final Map<String, List<Activity>> recentActivity = new ConcurrentHashMap<>();

    /**
     * Active user information
     */
    public static class ActiveUser {
        private String userId;
        private String username;
        private String email;
        private LocalDateTime joinedAt;
        private LocalDateTime lastActivity;
        private String currentEntity; // Entity being edited
        private String sessionId;
        private String ipAddress;
        private String color; // UI color for this user

        public ActiveUser(String userId, String username, String email) {
            this.userId = userId;
            this.username = username;
            this.email = email;
            this.joinedAt = LocalDateTime.now();
            this.lastActivity = LocalDateTime.now();
            this.sessionId = UUID.randomUUID().toString();
            this.color = generateColor(userId);
        }

        private String generateColor(String userId) {
            // Generate consistent color for user
            int hash = userId.hashCode();
            String[] colors = {
                "#FF6B6B", "#4ECDC4", "#45B7D1", "#FFA07A",
                "#98D8C8", "#F7DC6F", "#BB8FCE", "#85C1E2"
            };
            return colors[Math.abs(hash) % colors.length];
        }

        public void updateActivity() {
            this.lastActivity = LocalDateTime.now();
        }

        // Getters and setters
        public String getUserId() { return userId; }
        public String getUsername() { return username; }
        public String getEmail() { return email; }
        public LocalDateTime getJoinedAt() { return joinedAt; }
        public LocalDateTime getLastActivity() { return lastActivity; }
        public String getCurrentEntity() { return currentEntity; }
        public void setCurrentEntity(String entity) { this.currentEntity = entity; }
        public String getSessionId() { return sessionId; }
        public String getIpAddress() { return ipAddress; }
        public void setIpAddress(String ip) { this.ipAddress = ip; }
        public String getColor() { return color; }
    }

    /**
     * Lock information for entity editing
     */
    public static class LockInfo {
        private String entityIRI;
        private String lockedBy;
        private String username;
        private LocalDateTime lockedAt;
        private LocalDateTime expiresAt;

        public LockInfo(String entityIRI, String lockedBy, String username) {
            this.entityIRI = entityIRI;
            this.lockedBy = lockedBy;
            this.username = username;
            this.lockedAt = LocalDateTime.now();
            this.expiresAt = LocalDateTime.now().plusMinutes(30); // 30-minute lock
        }

        public boolean isExpired() {
            return LocalDateTime.now().isAfter(expiresAt);
        }

        // Getters
        public String getEntityIRI() { return entityIRI; }
        public String getLockedBy() { return lockedBy; }
        public String getUsername() { return username; }
        public LocalDateTime getLockedAt() { return lockedAt; }
        public LocalDateTime getExpiresAt() { return expiresAt; }
    }

    /**
     * Activity record
     */
    public static class Activity {
        private String userId;
        private String username;
        private String action;
        private String entityIRI;
        private String entityLabel;
        private LocalDateTime timestamp;

        public Activity(String userId, String username, String action) {
            this.userId = userId;
            this.username = username;
            this.action = action;
            this.timestamp = LocalDateTime.now();
        }

        // Getters and setters
        public String getUserId() { return userId; }
        public String getUsername() { return username; }
        public String getAction() { return action; }
        public String getEntityIRI() { return entityIRI; }
        public void setEntityIRI(String iri) { this.entityIRI = iri; }
        public String getEntityLabel() { return entityLabel; }
        public void setEntityLabel(String label) { this.entityLabel = label; }
        public LocalDateTime getTimestamp() { return timestamp; }
    }

    /**
     * User joins a project session
     */
    public ActiveUser joinSession(String projectId, String userId, String username, String email, String ipAddress) {
        ActiveUser user = new ActiveUser(userId, username, email);
        user.setIpAddress(ipAddress);
        
        activeSessions.computeIfAbsent(projectId, k -> ConcurrentHashMap.newKeySet()).add(user);
        
        // Record activity
        Activity activity = new Activity(userId, username, "joined session");
        recordActivity(projectId, activity);
        
        log.info("User {} joined project {}", username, projectId);
        
        return user;
    }

    /**
     * User leaves a project session
     */
    public void leaveSession(String projectId, String userId) {
        Set<ActiveUser> users = activeSessions.get(projectId);
        if (users != null) {
            users.removeIf(u -> u.getUserId().equals(userId));
            
            // Release any locks held by this user
            releaseUserLocks(userId);
            
            log.info("User {} left project {}", userId, projectId);
        }
    }

    /**
     * Get active users in a project
     */
    public Set<ActiveUser> getActiveUsers(String projectId) {
        Set<ActiveUser> users = activeSessions.getOrDefault(projectId, Collections.emptySet());
        
        // Remove inactive users (no activity in 5 minutes)
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        users.removeIf(u -> u.getLastActivity().isBefore(cutoff));
        
        return users;
    }

    /**
     * Update user's current activity
     */
    public void updateUserActivity(String projectId, String userId, String entityIRI) {
        Set<ActiveUser> users = activeSessions.get(projectId);
        if (users != null) {
            users.stream()
                .filter(u -> u.getUserId().equals(userId))
                .findFirst()
                .ifPresent(u -> {
                    u.updateActivity();
                    u.setCurrentEntity(entityIRI);
                });
        }
    }

    /**
     * Lock an entity for editing
     */
    public LockInfo lockEntity(String projectId, String entityIRI, String userId, String username) {
        // Check if already locked
        LockInfo existing = entityLocks.get(entityIRI);
        if (existing != null && !existing.isExpired()) {
            if (!existing.getLockedBy().equals(userId)) {
                // Already locked by someone else
                return null;
            }
            // Already locked by this user, return existing lock
            return existing;
        }
        
        // Create new lock
        LockInfo lock = new LockInfo(entityIRI, userId, username);
        entityLocks.put(entityIRI, lock);
        
        // Record activity
        Activity activity = new Activity(userId, username, "locked entity");
        activity.setEntityIRI(entityIRI);
        recordActivity(projectId, activity);
        
        log.info("Entity {} locked by {} in project {}", entityIRI, username, projectId);
        
        return lock;
    }

    /**
     * Unlock an entity
     */
    public boolean unlockEntity(String entityIRI, String userId) {
        LockInfo lock = entityLocks.get(entityIRI);
        if (lock != null && lock.getLockedBy().equals(userId)) {
            entityLocks.remove(entityIRI);
            log.info("Entity {} unlocked by {}", entityIRI, userId);
            return true;
        }
        return false;
    }

    /**
     * Check if entity is locked
     */
    public LockInfo getEntityLock(String entityIRI) {
        LockInfo lock = entityLocks.get(entityIRI);
        if (lock != null && lock.isExpired()) {
            entityLocks.remove(entityIRI);
            return null;
        }
        return lock;
    }

    /**
     * Release all locks held by a user
     */
    public void releaseUserLocks(String userId) {
        entityLocks.entrySet().removeIf(entry -> entry.getValue().getLockedBy().equals(userId));
        log.info("Released all locks for user {}", userId);
    }

    /**
     * Detect potential conflicts
     */
    public List<String> detectConflicts(String projectId, String entityIRI) {
        List<String> conflicts = new ArrayList<>();
        
        // Check if multiple users are editing the same entity
        Set<ActiveUser> users = getActiveUsers(projectId);
        List<String> editingUsers = users.stream()
            .filter(u -> entityIRI.equals(u.getCurrentEntity()))
            .map(ActiveUser::getUsername)
            .collect(Collectors.toList());
        
        if (editingUsers.size() > 1) {
            conflicts.add("Multiple users editing: " + String.join(", ", editingUsers));
        }
        
        // Check lock status
        LockInfo lock = getEntityLock(entityIRI);
        if (lock != null) {
            conflicts.add("Entity locked by: " + lock.getUsername());
        }
        
        return conflicts;
    }

    /**
     * Record an activity
     */
    public void recordActivity(String projectId, Activity activity) {
        recentActivity.computeIfAbsent(projectId, k -> new ArrayList<>()).add(activity);
        
        // Keep only last 100 activities
        List<Activity> activities = recentActivity.get(projectId);
        if (activities.size() > 100) {
            activities.remove(0);
        }
    }

    /**
     * Get recent activity for a project
     */
    public List<Activity> getRecentActivity(String projectId, int limit) {
        List<Activity> activities = recentActivity.getOrDefault(projectId, Collections.emptyList());
        
        // Return most recent activities
        int fromIndex = Math.max(0, activities.size() - limit);
        return new ArrayList<>(activities.subList(fromIndex, activities.size()));
    }

    /**
     * Get collaboration statistics
     */
    public Map<String, Object> getCollaborationStats(String projectId) {
        Map<String, Object> stats = new HashMap<>();
        
        Set<ActiveUser> users = getActiveUsers(projectId);
        stats.put("activeUsers", users.size());
        stats.put("users", users.stream()
            .map(u -> Map.of(
                "username", u.getUsername(),
                "joinedAt", u.getJoinedAt().toString(),
                "currentEntity", u.getCurrentEntity() != null ? u.getCurrentEntity() : ""
            ))
            .collect(Collectors.toList()));
        
        // Locked entities
        Map<String, LockInfo> projectLocks = entityLocks.entrySet().stream()
            .filter(e -> !e.getValue().isExpired())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        stats.put("lockedEntities", projectLocks.size());
        
        // Recent activity
        List<Activity> activities = getRecentActivity(projectId, 10);
        stats.put("recentActivityCount", activities.size());
        
        return stats;
    }

    /**
     * Broadcast a change to all active users
     * (In production, use WebSocket or SSE)
     */
    public void broadcastChange(String projectId, String userId, String changeType, String entityIRI) {
        Activity activity = new Activity(userId, getUserName(projectId, userId), changeType);
        activity.setEntityIRI(entityIRI);
        recordActivity(projectId, activity);
        
        log.info("Broadcasting change in project {}: {} on {}", projectId, changeType, entityIRI);
        
        // In production, push to WebSocket clients
    }

    /**
     * Cleanup expired locks and inactive users
     */
    public void cleanupInactiveSessions() {
        // Remove expired locks
        entityLocks.entrySet().removeIf(entry -> entry.getValue().isExpired());
        
        // Remove inactive users
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        activeSessions.values().forEach(users -> 
            users.removeIf(u -> u.getLastActivity().isBefore(cutoff))
        );
        
        log.debug("Cleaned up inactive sessions and expired locks");
    }

    // Helper methods
    
    private String getUserName(String projectId, String userId) {
        Set<ActiveUser> users = activeSessions.get(projectId);
        if (users != null) {
            return users.stream()
                .filter(u -> u.getUserId().equals(userId))
                .findFirst()
                .map(ActiveUser::getUsername)
                .orElse("Unknown");
        }
        return "Unknown";
    }
}