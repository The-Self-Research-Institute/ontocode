package self.research.ontology.owlEditor.model.collaboration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresenceMessage {

    private PresenceType type;

    private String projectId;

    private String userId;

    private String username;

    private String sessionId;

    private String color;

    private String cursorPosition;

    private String[] selectedNodes;

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
