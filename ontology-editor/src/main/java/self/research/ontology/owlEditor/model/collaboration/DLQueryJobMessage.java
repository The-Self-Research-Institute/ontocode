package self.research.ontology.owlEditor.model.collaboration;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class DLQueryJobMessage {
    private String jobId;
    private String projectId;
    private String status;
    private int queuePosition;
    private long estimatedWaitTimeMs;
    private String message;
    private Long timestamp;
    private Long executionTimeMs;
    private Map<String, Object> result;
    private String error;
    private DLQueryQueueStats queueStats;

    @Data
    @Builder
    public static class DLQueryQueueStats {
        private int activeJobs;
        private int queuedJobs;
        private long averageProcessingTimeMs;
        private int configuredMaxConcurrent;
        private int effectiveMaxConcurrent;
        private int slotBudget;
        private int usedSlots;
        private double freeHeapRatio;
        private String throttleReason;
    }
}
