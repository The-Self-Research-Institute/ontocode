package self.research.ontology.owlEditor.model.collaboration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Represents a single edit operation in collaborative editing.
 * This is the core message exchanged between clients for synchronization.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EditOperation {
    
    /**
     * Type of edit operation.
     */
    private OperationType type;
    
    /**
     * The project being edited.
     */
    private String projectId;
    
    /**
     * ID of the ontology node (class/property/individual URI).
     */
    private String nodeId;
    
    /**
     * The property being modified (e.g., "label", "comment", "subClassOf").
     */
    private String property;
    
    /**
     * New value for the property.
     */
    private Object value;
    
    /**
     * Previous value (for undo/conflict resolution).
     */
    private Object previousValue;
    
    /**
     * User who performed the operation.
     */
    private String userId;
    
    /**
     * Username for display purposes.
     */
    private String username;
    
    /**
     * WebSocket session ID.
     */
    private String sessionId;
    
    /**
     * Timestamp when the operation was created (client-side).
     */
    private long timestamp;
    
    /**
     * Server timestamp when received.
     */
    private long serverTimestamp;
    
    /**
     * Additional metadata (language tags, datatypes, etc.).
     */
    private Map<String, Object> metadata;
    
    /**
     * Version number for optimistic locking.
     */
    private Long version;
    
    public enum OperationType {
        // Class operations
        CLASS_ADDED,
        CLASS_MODIFIED,
        CLASS_DELETED,
        CLASS_RENAMED,
        
        // Property operations
        PROPERTY_ADDED,
        PROPERTY_MODIFIED,
        PROPERTY_DELETED,
        PROPERTY_RENAMED,
        
        // Individual operations
        INDIVIDUAL_ADDED,
        INDIVIDUAL_MODIFIED,
        INDIVIDUAL_DELETED,
        
        // Annotation operations
        ANNOTATION_ADDED,
        ANNOTATION_MODIFIED,
        ANNOTATION_DELETED,
        
        // Relationship operations
        SUBCLASS_ADDED,
        SUBCLASS_REMOVED,
        PROPERTY_DOMAIN_ADDED,
        PROPERTY_DOMAIN_REMOVED,
        PROPERTY_RANGE_ADDED,
        PROPERTY_RANGE_REMOVED,
        
        // Axiom operations
        AXIOM_ADDED,
        AXIOM_REMOVED,
        DISJOINT_ADDED,
        DISJOINT_REMOVED,
        EQUIVALENT_ADDED,
        EQUIVALENT_REMOVED,
        
        // Metadata operations (annotations, imports, GCIs)
        IMPORT_ADDED,
        IMPORT_REMOVED,
        ONTOLOGY_ANNOTATION_ADDED,
        ONTOLOGY_ANNOTATION_MODIFIED,
        ONTOLOGY_ANNOTATION_DELETED,
        GCI_ADDED,
        GCI_REMOVED,

        // SPARQL and special operations
        SPARQL_UPDATE,
        CHANGE_REVERTED,

        // Bulk operations
        BULK_IMPORT,
        BULK_DELETE
    }
}
