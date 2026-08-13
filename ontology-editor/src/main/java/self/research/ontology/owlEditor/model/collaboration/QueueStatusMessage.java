package self.research.ontology.owlEditor.model.collaboration;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class QueueStatusMessage {
    private String projectId;
    private int queuePosition;
    private int totalInQueue;
    private long estimatedWaitTimeMs;
    private String status;
    private String message;
    private Long timestamp;
    private QueueStats queueStats;

    @Data
    @Builder
    public static class QueueStats {
        private int activeImports;
        private int queuedImports;
        private long averageProcessingTimeMs;
        private List<QueuedProject> queue;

        private List<String> activeProjectIds;
    }

    @Data
    @Builder
    public static class QueuedProject {
        private String projectId;
        private String filename;
        private int position;
        private long estimatedWaitTimeMs;
        private long queuedSinceMs;
    }
}
