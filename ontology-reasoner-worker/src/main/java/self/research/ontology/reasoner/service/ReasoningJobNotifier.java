package self.research.ontology.reasoner.service;

import self.research.ontology.reasoner.model.ReasoningJobEvent;

public final class ReasoningJobNotifier {

    private ReasoningJobNotifier() {}

    public record JobSnapshot(
            String jobId,
            String jobType,
            String projectId,
            String status,
            int queuePosition,
            long estimatedWaitTimeMs,
            String message,
            Long timestamp,
            Long executionTimeMs,
            java.util.Map<String, Object> result,
            String error,
            ReasoningJobEvent.QueueStats queueStats
    ) {}

    public static JobSnapshot fromEvent(ReasoningJobEvent event) {
        return new JobSnapshot(
                event.getJobId(),
                event.getJobType(),
                event.getProjectId(),
                event.getStatus(),
                event.getQueuePosition(),
                event.getEstimatedWaitTimeMs(),
                event.getMessage(),
                event.getTimestamp(),
                event.getExecutionTimeMs(),
                event.getResult(),
                event.getError(),
                event.getQueueStats()
        );
    }
}
