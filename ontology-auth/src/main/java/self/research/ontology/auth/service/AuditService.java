package self.research.ontology.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final MongoTemplate mongoTemplate;

    public AuditService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public void logEvent(String username, String eventType, String ipAddress, Map<String, String> metadata) {
        try {
            Map<String, Object> auditEvent = new HashMap<>();
            auditEvent.put("username", username);
            auditEvent.put("eventType", eventType);
            auditEvent.put("ipAddress", ipAddress);
            auditEvent.put("timestamp", LocalDateTime.now());
            auditEvent.put("metadata", metadata != null ? metadata : new HashMap<>());

            mongoTemplate.save(auditEvent, "audit_events");

            log.info("Audit: {} - {} from {}", eventType, username, ipAddress);
        } catch (Exception e) {
            log.error("Failed to log audit event", e);
        }
    }

    public void logLoginSuccess(String username, String ipAddress) {
        logEvent(username, "LOGIN_SUCCESS", ipAddress, null);
    }

    public void logLoginFailure(String username, String ipAddress, String reason) {
        logEvent(username, "LOGIN_FAIL", ipAddress, Map.of("reason", reason));
    }

    public void logAccountLocked(String username, String ipAddress) {
        logEvent(username, "ACCOUNT_LOCKED", ipAddress, null);
    }

    public void logRateLimitHit(String username, String ipAddress) {
        logEvent(username, "RATE_LIMIT_HIT", ipAddress, null);
    }

    public void logSignup(String username, String email) {
        logEvent(username, "SIGNUP_SUCCESS", null, Map.of("email", email));
    }

    public void logEmailVerified(String username) {
        logEvent(username, "EMAIL_VERIFIED", null, null);
    }

    public void logPasswordResetRequest(String username, String ipAddress) {
        logEvent(username, "PASSWORD_RESET_REQUESTED", ipAddress, null);
    }

    public void logPasswordResetSuccess(String username) {
        logEvent(username, "PASSWORD_RESET_SUCCESS", null, null);
    }

    public void logPasswordChange(String username) {
        logEvent(username, "PASSWORD_CHANGED", null, null);
    }
}