package self.research.ontology.owlEditor.model;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Represents an import job in the queue
 */
@Data
@Builder
public class ImportQueueItem {
    private String projectId;
    private String filename;
    private String ownerEmail;
    private Path owlFile;
    private ImportOptions.ImportMode importMode;
    private ImportOptions.PartitionStrategy partitionStrategy;
    private long fileSizeBytes;
    private Integer classCount;
    private Integer annotationCount;
    private Instant queuedAt;
    private Instant startedAt;
    private ImportStatus status;
    private long estimatedDurationMs;
    private int queuePosition;
    private int retryCount;
    private int maxRetries;
    private String failureReason;
    private Instant lastAttemptAt;

    public enum ImportStatus {
        QUEUED,
        PROCESSING,
        COMPLETED,
        FAILED,
        RETRYING
    }

    public long getWaitTimeMs() {
        if (queuedAt == null) {
            return 0;
        }
        Instant now = startedAt != null ? startedAt : Instant.now();
        return now.toEpochMilli() - queuedAt.toEpochMilli();
    }
}
