package self.research.ontology.owlEditor.model.collaboration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a lock on an ontology node to prevent concurrent edits.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LockMessage {
    
    /**
     * Type of lock operation.
     */
    private LockType type;
    
    /**
     * Project ID.
     */
    private String projectId;
    
    /**
     * Node ID being locked/unlocked (URI).
     */
    private String nodeId;
    
    /**
     * User who holds/requests the lock.
     */
    private String userId;
    
    /**
     * Username for display.
     */
    private String username;
    
    /**
     * Session ID.
     */
    private String sessionId;
    
    /**
     * Lock expiration timestamp (auto-release).
     */
    private long expiresAt;
    
    /**
     * Timestamp.
     */
    private long timestamp;
    
    /**
     * Whether the lock was successfully acquired.
     */
    private boolean success;
    
    /**
     * Error message if lock failed.
     */
    private String error;
    
    public enum LockType {
        LOCK_ACQUIRED,
        LOCK_RELEASED,
        LOCK_EXPIRED,
        LOCK_DENIED,
        LOCK_REQUEST,
        LOCK_FORCE_RELEASE
    }
}
