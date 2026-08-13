package self.research.ontology.owlEditor.model.collaboration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportStatusMessage {

    private ImportStatusType type;

    private String projectId;

    private String status;

    private String statusMessage;

    private String filename;

    private Integer progress;

    private long timestamp;

    private Object metadata;

    public enum ImportStatusType {
        IMPORT_STARTED,
        IMPORT_PROGRESS,
        IMPORT_COMPLETED,
        IMPORT_FAILED
    }
}
