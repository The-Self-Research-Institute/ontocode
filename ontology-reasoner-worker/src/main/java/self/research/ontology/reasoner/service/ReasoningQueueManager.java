package self.research.ontology.reasoner.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import self.research.ontology.common.ReasoningFriendlyErrors;
import self.research.ontology.reasoner.model.ReasoningJob;
import self.research.ontology.reasoner.model.ReasoningJobEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class ReasoningQueueManager {

    private final EditorClient editorClient;
    private final ReasoningConcurrencyPolicy concurrencyPolicy;
    private final ReasoningResultRetention resultRetention;
    private final LinkedList<ReasoningJob> queue = new LinkedList<>();
    private final Map<String, ReasoningJob> activeJobs = new ConcurrentHashMap<>();
    private final AtomicLong totalDurationMs = new AtomicLong(0);
    private final AtomicLong completedCount = new AtomicLong(0);
    private volatile Instant lastActivityAt = Instant.now();

    private static final long DEFAULT_ESTIMATE_MS = 120_000;
    private static final long MAX_WAIT_TIME_MS = 30 * 60 * 1000;

    public ReasoningQueueManager(EditorClient editorClient,
                                 ReasoningConcurrencyPolicy concurrencyPolicy,
                                 ReasoningResultRetention resultRetention) {
        this.editorClient = editorClient;
        this.concurrencyPolicy = concurrencyPolicy;
        this.resultRetention = resultRetention;
    }

    public synchronized ReasoningJob enqueue(ReasoningJob.JobType jobType,
                                             String projectId,
                                             String expression,
                                             List<String> queryTypes,
                                             String reasonerType,
                                             String ownerEmail) {
        long tripleCount = editorClient.getTripleCount(projectId);
        int slotWeight = concurrencyPolicy.slotWeight(tripleCount);

        resultRetention.makeRoomForIncomingJob();

        ReasoningJob job = ReasoningJob.builder()
                .jobId(UUID.randomUUID().toString())
                .jobType(jobType)
                .projectId(projectId)
                .expression(expression)
                .queryTypes(queryTypes)
                .reasonerType(reasonerType)
                .status(ReasoningJob.Status.QUEUED)
                .queuePosition(queue.size() + 1)
                .estimatedWaitTimeMs(0)
                .queuedAt(Instant.now())
                .ownerEmail(ownerEmail)
                .tripleCount(tripleCount)
                .slotWeight(slotWeight)
                .build();

        queue.addLast(job);
        updateQueuePositions();
        notifyJob(job);
        touchActivity();
        log.info("[Reasoning] Enqueued {} job {} for project {} (position {})",
                jobType, job.getJobId(), projectId, job.getQueuePosition());
        return job;
    }

    public synchronized ReasoningJob dequeue() {
        ReasoningJob next = queue.peekFirst();
        if (next == null || !concurrencyPolicy.canStartAnother(activeJobs.values(), next)) {
            return null;
        }

        ReasoningJob job = queue.removeFirst();
        job.setStatus(ReasoningJob.Status.PROCESSING);
        job.setStartedAt(Instant.now());
        job.setQueuePosition(0);
        activeJobs.put(job.getJobId(), job);
        updateQueuePositions();
        notifyJob(job);
        touchActivity();
        return job;
    }

    public synchronized void markCompleted(ReasoningJob job, Map<String, Object> result, long durationMs) {
        activeJobs.remove(job.getJobId());
        job.setStatus(ReasoningJob.Status.COMPLETED);
        job.setCompletedAt(Instant.now());
        job.setExecutionTimeMs(durationMs);
        job.setResult(result);
        resultRetention.retain(job);
        recordDuration(durationMs);
        notifyJob(job);
        touchActivity();
    }

    public synchronized void markFailed(ReasoningJob job, String error) {
        activeJobs.remove(job.getJobId());
        job.setStatus(ReasoningJob.Status.FAILED);
        job.setCompletedAt(Instant.now());
        job.setError(error);
        resultRetention.retain(job);
        notifyJob(job);
        touchActivity();
        log.error("[Reasoning] Failed job {}: {}", job.getJobId(), error);
    }

    public ReasoningJob getJob(String jobId) {
        ReasoningJob active = activeJobs.get(jobId);
        if (active != null) {
            return active;
        }
        ReasoningJob queued = queue.stream().filter(j -> jobId.equals(j.getJobId())).findFirst().orElse(null);
        if (queued != null) {
            return queued;
        }
        return resultRetention.get(jobId).orElse(null);
    }

    public int activeJobCount() {
        return activeJobs.size();
    }

    public synchronized int queuedJobCount() {
        return queue.size();
    }

    private void touchActivity() {
        lastActivityAt = Instant.now();
    }

    public synchronized long getEstimatedWaitTimeMs(String jobId) {
        ReasoningJob job = getJob(jobId);
        return job == null ? 0 : calculateEstimatedWaitTime(job);
    }

    public synchronized ReasoningJobEvent.QueueStats getQueueStats() {
        ReasoningConcurrencyPolicy.ConcurrencySnapshot snap = concurrencyPolicy.snapshot(activeJobs.values());
        return ReasoningJobEvent.QueueStats.builder()
                .activeJobs(activeJobs.size())
                .queuedJobs(queue.size())
                .averageProcessingTimeMs(getAverageProcessingTimeMs())
                .configuredMaxConcurrent(snap.configuredMax())
                .effectiveMaxConcurrent(snap.effectiveMax())
                .slotBudget(snap.slotBudget())
                .usedSlots(snap.usedSlots())
                .freeHeapRatio(snap.freeHeapRatio())
                .throttleReason(snap.reason())
                .build();
    }

    public synchronized boolean canProcess() {
        ReasoningJob next = queue.peekFirst();
        return next != null && concurrencyPolicy.canStartAnother(activeJobs.values(), next);
    }

    public synchronized boolean hasQueuedJobs() {
        return !queue.isEmpty();
    }

    private void updateQueuePositions() {
        int position = 1;
        for (ReasoningJob job : queue) {
            job.setQueuePosition(position++);
            job.setEstimatedWaitTimeMs(calculateEstimatedWaitTime(job));
            notifyJob(job);
        }
    }

    private long calculateEstimatedWaitTime(ReasoningJob target) {
        if (target.getStatus() == ReasoningJob.Status.PROCESSING) {
            return 0;
        }
        long waitMs = activeJobs.values().stream().mapToLong(this::estimateRemainingTimeMs).sum();
        for (ReasoningJob job : queue) {
            if (job.getJobId().equals(target.getJobId())) {
                break;
            }
            waitMs += estimateDurationForJob(job);
        }
        return Math.min(waitMs, MAX_WAIT_TIME_MS);
    }

    private long estimateDurationForJob(ReasoningJob job) {
        long base = getAverageProcessingTimeMs();
        return (long) (base * Math.max(1, job.getSlotWeight()) * 0.75);
    }

    private long estimateRemainingTimeMs(ReasoningJob job) {
        long estimate = estimateDurationForJob(job);
        if (job.getStartedAt() == null) {
            return estimate;
        }
        long elapsed = Instant.now().toEpochMilli() - job.getStartedAt().toEpochMilli();
        return Math.max(0, estimate - elapsed);
    }

    private long getAverageProcessingTimeMs() {
        long count = completedCount.get();
        return count == 0 ? DEFAULT_ESTIMATE_MS : totalDurationMs.get() / count;
    }

    private void recordDuration(long durationMs) {
        totalDurationMs.addAndGet(durationMs);
        completedCount.incrementAndGet();
    }

    private void notifyJob(ReasoningJob job) {
        ReasoningJobEvent event = ReasoningJobEvent.builder()
                .jobId(job.getJobId())
                .jobType(job.getJobType() != null ? job.getJobType().name() : null)
                .projectId(job.getProjectId())
                .status(job.getStatus().name())
                .queuePosition(job.getQueuePosition())
                .estimatedWaitTimeMs(calculateEstimatedWaitTime(job))
                .executionTimeMs(job.getExecutionTimeMs())
                .result(job.getResult())
                .error(job.getStatus() == ReasoningJob.Status.FAILED
                        ? ReasoningFriendlyErrors.forUser(job.getError()) : job.getError())
                .message(buildMessage(job))
                .timestamp(System.currentTimeMillis())
                .queueStats(getQueueStats())
                .build();
        editorClient.publishJobEvent(ReasoningJobNotifier.fromEvent(event));
    }

    private String buildMessage(ReasoningJob job) {
        long waitSec = Math.max(0, calculateEstimatedWaitTime(job) / 1000);
        return switch (job.getStatus()) {
            case QUEUED -> formatQueuedMessage(job.getQueuePosition(), waitSec, job.getJobType());
            case PROCESSING -> processingMessage(job.getJobType());
            case COMPLETED -> completedMessage(job.getJobType());
            case FAILED -> ReasoningFriendlyErrors.forUser(job.getError());
        };
    }

    private String formatQueuedMessage(int position, long waitSec, ReasoningJob.JobType type) {
        String task = taskLabel(type);
        if (position <= 1) {
            return waitSec > 0
                    ? String.format("Your %s is next in line (about %d sec)", task, waitSec)
                    : String.format("Your %s is next in line", task);
        }
        int ahead = Math.max(0, position - 1);
        if (waitSec > 0) {
            return String.format("Your %s is queued — position %d (%d ahead, about %d sec)",
                    task, position, ahead, waitSec);
        }
        return String.format("Your %s is queued — position %d (%d ahead)", task, position, ahead);
    }

    private static String taskLabel(ReasoningJob.JobType type) {
        if (type == null || type == ReasoningJob.JobType.DL_QUERY) {
            return "query";
        }
        return "reasoning task";
    }

    private static String processingMessage(ReasoningJob.JobType type) {
        if (type == ReasoningJob.JobType.DL_QUERY) {
            return "Running your query…";
        }
        return "Running reasoning…";
    }

    private static String completedMessage(ReasoningJob.JobType type) {
        if (type == ReasoningJob.JobType.DL_QUERY) {
            return "Query complete";
        }
        return "Reasoning complete";
    }

    public static String friendlyError(String raw) {
        return ReasoningFriendlyErrors.forUser(raw);
    }

    Collection<ReasoningJob> activeJobsView() {
        return activeJobs.values();
    }

    synchronized List<ReasoningJob> snapshotQueuedJobs() {
        return new ArrayList<>(queue);
    }
}
