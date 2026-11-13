package self.research.ontology.owlEditor.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AssistantIdempotencyRecordDocument {

    private String id;

    private String actor;
    private String method;
    private String path;
    private String bodyHash;
    private State state;
    private String ownerToken;

    private Integer responseStatus;
    private String responseContentType;
    private String responseBody;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant expiresAt;

    public enum State {
        IN_FLIGHT, COMPLETED
    }
}
