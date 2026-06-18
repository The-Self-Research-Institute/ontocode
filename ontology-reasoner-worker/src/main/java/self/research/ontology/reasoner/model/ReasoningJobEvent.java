package self.research.ontology.reasoner.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class ReasoningJobEvent {
    private String jobId;
    private String jobType;
    private String projectId;
    private String status;
    private int queuePosition;
    private long estimatedWaitTimeMs;
    private String message;
    private Long timestamp;
    private Long executionTimeMs;
    private Map<String, Object> result;
    private String error;
    private QueueStats queueStats;

    @Data
    @Builder
    public static class QueueStats {
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
