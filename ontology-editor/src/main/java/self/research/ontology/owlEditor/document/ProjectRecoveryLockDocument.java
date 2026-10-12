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
@Document(collection = "project_recovery_locks")
public class ProjectRecoveryLockDocument {

    @Id
    private String id;

    private boolean locked;
    private String reason;
    private Instant lockedAt;
    private String operationId;
    private boolean canRestore;

    private String releasedBy;
    private String releaseAction;
    private Instant releasedAt;

    private Instant updatedAt;
}
