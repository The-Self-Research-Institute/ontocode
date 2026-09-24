package self.research.ontology.owlEditor.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "assistant_idempotency")
public class AssistantIdempotencyRecordDocument {

    @Id
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
