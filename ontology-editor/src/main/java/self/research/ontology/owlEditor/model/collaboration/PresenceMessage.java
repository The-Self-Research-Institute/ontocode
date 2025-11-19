package self.research.ontology.owlEditor.model.collaboration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents user presence information in collaborative editing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresenceMessage {
    
    /**
     * Type of presence event.
     */
    private PresenceType type;
    
    /**
     * Project ID.
     */
    private String projectId;
    
    /**
     * User ID.
     */
    private String userId;
    
    /**
     * Username for display.
     */
    private String username;
    
    /**
     * WebSocket session ID.
     */
    private String sessionId;
    
    /**
     * Assigned color for this user (hex).
     */
    private String color;
    
    /**
     * Current cursor position (node URI).
     */
    private String cursorPosition;
    
    /**
     * Selected nodes (URIs).
     */
    private String[] selectedNodes;
    
    /**
     * Timestamp.
     */
    private long timestamp;
    
    public enum PresenceType {
        USER_JOINED,
        USER_LEFT,
        CURSOR_MOVED,
        SELECTION_CHANGED,
        USER_IDLE,
        USER_ACTIVE
    }
}
