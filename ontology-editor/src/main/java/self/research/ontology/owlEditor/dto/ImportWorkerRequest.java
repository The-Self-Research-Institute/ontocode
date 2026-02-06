package self.research.ontology.owlEditor.dto;

import lombok.Data;

@Data
public class ImportWorkerRequest {
    private String projectId;
    private String filename;
    private String ownerEmail;
    private String gridfsFileId;
    private String importMode;
    private String partition;
    private Long fileSizeBytes;
}
