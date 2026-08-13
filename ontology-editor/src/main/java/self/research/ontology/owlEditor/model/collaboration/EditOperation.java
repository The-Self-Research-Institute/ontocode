package self.research.ontology.owlEditor.model.collaboration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EditOperation {

    private OperationType type;

    private String projectId;

    private String nodeId;

    private String property;

    private Object value;

    private Object previousValue;

    private String userId;

    private String username;

    private String sessionId;

    private long timestamp;

    private long serverTimestamp;

    private Map<String, Object> metadata;

    private Long version;

    public enum OperationType {

        CLASS_ADDED,
        CLASS_MODIFIED,
        CLASS_DELETED,
        CLASS_RENAMED,

        PROPERTY_ADDED,
        PROPERTY_MODIFIED,
        PROPERTY_DELETED,
        PROPERTY_RENAMED,

        INDIVIDUAL_ADDED,
        INDIVIDUAL_MODIFIED,
        INDIVIDUAL_DELETED,

        ANNOTATION_ADDED,
        ANNOTATION_MODIFIED,
        ANNOTATION_DELETED,

        SUBCLASS_ADDED,
        SUBCLASS_REMOVED,
        PROPERTY_DOMAIN_ADDED,
        PROPERTY_DOMAIN_REMOVED,
        PROPERTY_RANGE_ADDED,
        PROPERTY_RANGE_REMOVED,

        AXIOM_ADDED,
        AXIOM_REMOVED,
        DISJOINT_ADDED,
        DISJOINT_REMOVED,
        EQUIVALENT_ADDED,
        EQUIVALENT_REMOVED,

        IMPORT_ADDED,
        IMPORT_REMOVED,
        ONTOLOGY_ANNOTATION_ADDED,
        ONTOLOGY_ANNOTATION_MODIFIED,
        ONTOLOGY_ANNOTATION_DELETED,
        GCI_ADDED,
        GCI_REMOVED,

        SPARQL_UPDATE,
        CHANGE_REVERTED,

        BULK_IMPORT,
        BULK_DELETE
    }
}
