package self.research.ontology.owlEditor.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class DLQueryJob {

    public enum Status {
        QUEUED, PROCESSING, COMPLETED, FAILED
    }

    private String jobId;
    private String projectId;
    private String expression;
    private List<String> queryTypes;
    private Status status;
    private int queuePosition;
    private long estimatedWaitTimeMs;
    private Instant queuedAt;
    private Instant startedAt;
    private Instant completedAt;
    private long executionTimeMs;
    private Map<String, Object> result;
    private String error;
    private String ownerEmail;

    private long tripleCount;

    private int slotWeight;

    public long getWaitTimeMs() {
        if (queuedAt == null) {
            return 0;
        }
        Instant end = startedAt != null ? startedAt : Instant.now();
        return Math.max(0, end.toEpochMilli() - queuedAt.toEpochMilli());
    }
}
