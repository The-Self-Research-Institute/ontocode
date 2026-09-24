package self.research.ontology.owlEditor.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "assistant_sessions")
public class AssistantSessionDocument {

    @Id
    private String id;

    private String projectId;
    private String userEmail;
    private String documentPath;
    private String actionType;
    private String actionContext;
    private String provider;
    private String model;

    private Long pinnedRevision;

    private AssistantSessionStatus status;

    private Integer retrievalAttemptsRemaining;

    private Integer tokenBudgetRemaining;

    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;

    public enum AssistantSessionStatus {
        ACTIVE, EXPIRED, COMPLETED
    }
}
