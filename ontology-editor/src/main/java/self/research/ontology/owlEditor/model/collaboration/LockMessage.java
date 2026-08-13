package self.research.ontology.owlEditor.model.collaboration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LockMessage {

    private LockType type;

    private String projectId;

    private String nodeId;

    private String userId;

    private String username;

    private String sessionId;

    private long expiresAt;

    private long timestamp;

    private boolean success;

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
