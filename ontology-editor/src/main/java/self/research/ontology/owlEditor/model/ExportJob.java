package self.research.ontology.owlEditor.model;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.time.Instant;

@Data
@Builder(toBuilder = true)
public class ExportJob {

    public enum Status {
        PENDING, PROCESSING, COMPLETED, ERROR
    }

    private String jobId;
    private String projectId;
    private String format;
    private Status status;
    private Path resultPath;
    private String error;
    private Instant createdAt;
    private Instant completedAt;
}
