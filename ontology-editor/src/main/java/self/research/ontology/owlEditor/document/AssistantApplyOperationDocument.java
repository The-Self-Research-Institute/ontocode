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
@Document(collection = "assistant_apply_operations")
public class AssistantApplyOperationDocument {

    @Id
    private String id;

    private String projectId;
    private String groupId;
    private String sessionId;
    private String actor;
    private String targetPath;

    private ApplyOperationStatus status;

    private String snapshotPath;
    private String failureReason;
    private String resolution;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant resolvedAt;

    public boolean isUnresolved() {
        if (status == null) {
            return false;
        }
        return switch (status) {
            case PREPARED, GRAPH_IMPORTING -> true;
            case FAILED -> resolvedAt == null;
            case COMMITTED, ROLLED_BACK -> false;
        };
    }

    public enum ApplyOperationStatus {
        PREPARED, GRAPH_IMPORTING, COMMITTED, FAILED, ROLLED_BACK
    }
}
