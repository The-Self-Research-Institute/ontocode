package self.research.ontology.owlEditor.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "undo_intents")
@CompoundIndex(name = "project_op", def = "{'projectId': 1, 'opId': 1}", unique = true, sparse = true)
public class UndoIntentDocument {

    public enum Status { PENDING, COMMITTED, FAILED, INTERRUPTED, NEEDS_ATTENTION }

    public enum Stage { STARTED, GRAPH_APPLIED, DOCUMENTS_APPLIED, HISTORY_MARKED }

    @Id
    private String id;

    private String projectId;
    private String opId;
    private String direction;
    private String actor;
    private boolean draft;
    private List<String> changeIds;
    private String auditId;
    private String undoAuditId;
    private String scopeKey;
    private Stage stage;
    private int appliedCount;
    private Status status;
    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;
}
