package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import self.research.ontology.common.ReasoningFriendlyErrors;
import self.research.ontology.owlEditor.model.DLQueryJob;
import self.research.ontology.owlEditor.model.collaboration.DLQueryJobMessage;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Service
@ConditionalOnProperty(name = "ontocode.reasoner-worker.enabled", havingValue = "true")
public class ReasoningJobRelayService {

    private final SimpMessagingTemplate messagingTemplate;
    private final EditorJobResultRetention jobRetention;

    public ReasoningJobRelayService(SimpMessagingTemplate messagingTemplate,
                                    EditorJobResultRetention jobRetention) {
        this.messagingTemplate = messagingTemplate;
        this.jobRetention = jobRetention;
    }

    public DLQueryJob getJob(String jobId) {
        return jobRetention.get(jobId).orElse(null);
    }

    public synchronized void rememberSubmittedJob(String jobId,
                                     String projectId,
                                     String expression,
                                     String jobType,
                                     Map<String, Object> workerResponse) {
        DLQueryJob existing = jobRetention.get(jobId).orElse(null);
        if (existing != null) {
            if (existing.getProjectId() == null) {
                existing.setProjectId(projectId);
            }
            if (existing.getExpression() == null) {
                existing.setExpression(expression);
            }
            jobRetention.retain(existing);
            return;
        }
        jobRetention.makeRoomForIncomingJob();

        DLQueryJob job = DLQueryJob.builder()
                .jobId(jobId)
                .projectId(projectId)
                .expression(expression)
                .status(parseStatus(workerResponse.get("status")))
                .queuePosition(intVal(workerResponse.get("queuePosition"), 1))
                .estimatedWaitTimeMs(longVal(workerResponse.get("estimatedWaitTimeMs")))
                .queuedAt(Instant.now())
                .build();
        jobRetention.retain(job);
        notify(job, workerResponse);
    }

    @SuppressWarnings("unchecked")
    public synchronized void applyWorkerEvent(Map<String, Object> payload) {
        String jobId = stringVal(payload.get("jobId"));
        if (jobId == null) {
            return;
        }

        DLQueryJob existing = jobRetention.get(jobId).orElse(null);
        DLQueryJob.Status incoming = parseStatus(payload.get("status"));
        if (existing != null && isTerminal(existing.getStatus()) && !isTerminal(incoming)) {
            log.debug("Ignoring late {} event for job {} that already finished as {}",
                    incoming, jobId, existing.getStatus());
            return;
        }
        DLQueryJob job = existing != null ? existing : DLQueryJob.builder().jobId(jobId).build();
        if (payload.get("projectId") != null) {
            job.setProjectId(stringVal(payload.get("projectId")));
        }
        job.setStatus(incoming);
        job.setQueuePosition(intVal(payload.get("queuePosition"), 0));
        job.setEstimatedWaitTimeMs(longVal(payload.get("estimatedWaitTimeMs")));
        if (payload.get("executionTimeMs") instanceof Number n) {
            job.setExecutionTimeMs(n.longValue());
        }
        if (payload.get("result") instanceof Map<?, ?> result) {
            job.setResult((Map<String, Object>) result);
        }
        if (payload.get("error") != null) {
            job.setError(stringVal(payload.get("error")));
        }
        if ("COMPLETED".equals(job.getStatus().name()) || "FAILED".equals(job.getStatus().name())) {
            job.setCompletedAt(Instant.now());
        }
        jobRetention.retain(job);
        notify(job, payload);
    }

    public synchronized DLQueryJob markLost(String jobId, String error) {
        return failIfUnfinished(jobId, error);
    }

    public synchronized DLQueryJob markStopped(String jobId, String message) {
        return failIfUnfinished(jobId, message);
    }

    private DLQueryJob failIfUnfinished(String jobId, String error) {
        DLQueryJob job = jobRetention.get(jobId).orElse(null);
        if (job == null || isTerminal(job.getStatus())) {
            return job;
        }
        job.setStatus(DLQueryJob.Status.FAILED);
        job.setError(error);
        job.setQueuePosition(0);
        job.setEstimatedWaitTimeMs(0);
        job.setCompletedAt(Instant.now());
        jobRetention.retain(job);
        notify(job, Map.of());
        return job;
    }

    private static boolean isTerminal(DLQueryJob.Status status) {
        return status == DLQueryJob.Status.COMPLETED || status == DLQueryJob.Status.FAILED;
    }

    private void notify(DLQueryJob job, Map<String, Object> payload) {
        DLQueryJobMessage.DLQueryQueueStats stats = null;
        if (payload.get("queueStats") instanceof Map<?, ?> raw) {
            stats = DLQueryJobMessage.DLQueryQueueStats.builder()
                    .activeJobs(intVal(raw.get("activeJobs"), 0))
                    .queuedJobs(intVal(raw.get("queuedJobs"), 0))
                    .averageProcessingTimeMs(longVal(raw.get("averageProcessingTimeMs")))
                    .configuredMaxConcurrent(intVal(raw.get("configuredMaxConcurrent"), 1))
                    .effectiveMaxConcurrent(intVal(raw.get("effectiveMaxConcurrent"), 1))
                    .slotBudget(intVal(raw.get("slotBudget"), 1))
                    .usedSlots(intVal(raw.get("usedSlots"), 0))
                    .freeHeapRatio(raw.get("freeHeapRatio") instanceof Number n ? n.doubleValue() : 0)
                    .throttleReason(stringVal(raw.get("throttleReason")))
                    .build();
        }

        String message = stringVal(payload.get("message"));
        if (message == null) {
            message = switch (job.getStatus()) {
                case QUEUED -> "Your task is queued";
                case PROCESSING -> "Running…";
                case COMPLETED -> "Complete";
                case FAILED -> ReasoningFriendlyErrors.forUser(job.getError());
            };
        }

        DLQueryJobMessage ws = DLQueryJobMessage.builder()
                .jobId(job.getJobId())
                .projectId(job.getProjectId())
                .status(job.getStatus().name())
                .queuePosition(job.getQueuePosition())
                .estimatedWaitTimeMs(job.getEstimatedWaitTimeMs())
                .executionTimeMs(job.getExecutionTimeMs())
                .result(job.getResult())
                .error(job.getStatus() == DLQueryJob.Status.FAILED
                        ? ReasoningFriendlyErrors.forUser(job.getError()) : job.getError())
                .message(message)
                .timestamp(System.currentTimeMillis())
                .queueStats(stats)
                .build();

        try {
            messagingTemplate.convertAndSend("/topic/dlquery/" + job.getJobId(), ws);
            messagingTemplate.convertAndSend("/topic/reasoning/" + job.getJobId(), ws);
        } catch (Exception e) {
            log.warn("WebSocket notify failed for job {}: {}", job.getJobId(), e.getMessage());
        }
    }

    private static DLQueryJob.Status parseStatus(Object raw) {
        if (raw == null) {
            return DLQueryJob.Status.QUEUED;
        }
        try {
            return DLQueryJob.Status.valueOf(raw.toString());
        } catch (Exception e) {
            return DLQueryJob.Status.QUEUED;
        }
    }

    private static String stringVal(Object raw) {
        return raw != null ? raw.toString() : null;
    }

    private static int intVal(Object raw, int fallback) {
        return raw instanceof Number n ? n.intValue() : fallback;
    }

    private static long longVal(Object raw) {
        return raw instanceof Number n ? n.longValue() : 0L;
    }
}
