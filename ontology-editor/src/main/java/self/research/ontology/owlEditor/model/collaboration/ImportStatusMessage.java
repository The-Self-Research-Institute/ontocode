package self.research.ontology.owlEditor.model.collaboration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocket message for notifying clients about ontology import status changes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportStatusMessage {

    /**
     * Type of import status event.
     */
    private ImportStatusType type;

    /**
     * Project ID.
     */
    private String projectId;

    /**
     * Current status (UPLOADED, PROCESSING, COMPLETED, ERROR).
     */
    private String status;

    /**
     * Status message/description.
     */
    private String statusMessage;

    /**
     * Filename being imported.
     */
    private String filename;

    /**
     * Progress percentage (0-100), optional.
     */
    private Integer progress;

    /**
     * Timestamp of status change.
     */
    private long timestamp;

    /**
     * Additional metadata (e.g., triple count, error details).
     */
    private Object metadata;

    public enum ImportStatusType {
        IMPORT_STARTED,
        IMPORT_PROGRESS,
        IMPORT_COMPLETED,
        IMPORT_FAILED
    }
}
