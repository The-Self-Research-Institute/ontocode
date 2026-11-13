package self.research.ontology.owlEditor.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectRecoveryLockDocument {

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
