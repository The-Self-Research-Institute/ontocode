package self.research.ontology.owlEditor.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.DLQueryJob;
import self.research.ontology.owlEditor.model.collaboration.DLQueryJobMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class DLQueryQueueManager {

    private final SimpMessagingTemplate messagingTemplate;
    private final SparqlDatasetService datasetService;
    private final DLQueryConcurrencyPolicy concurrencyPolicy;
    private final LinkedList<DLQueryJob> queue = new LinkedList<>();
    private final Map<String, DLQueryJob> activeJobs = new ConcurrentHashMap<>();
    private final AtomicLong totalDurationMs = new AtomicLong(0);
    private final AtomicLong completedCount = new AtomicLong(0);

    private final Cache<String, DLQueryJob> finishedJobs = Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterWrite(15, TimeUnit.MINUTES)
            .build();

    private static final long DEFAULT_ESTIMATE_MS = 120_000;
    private static final long MAX_WAIT_TIME_MS = 30 * 60 * 1000;

    public DLQueryQueueManager(SimpMessagingTemplate messagingTemplate,
                                 SparqlDatasetService datasetService,
                                 DLQueryConcurrencyPolicy concurrencyPolicy) {
        this.messagingTemplate = messagingTemplate;
        this.datasetService = datasetService;
        this.concurrencyPolicy = concurrencyPolicy;
    }

    public synchronized DLQueryJob enqueue(String projectId,
                                             String expression,
                                             List<String> queryTypes,
                                             String ownerEmail) {
        DLQueryJob existing = findActiveOrQueuedJob(projectId, expression);
        if (existing != null) {
            log.info("[DLQuery] Reusing job {} for project {}", existing.getJobId(), projectId);
            notifyJob(existing);
            return existing;
        }

        long tripleCount = resolveTripleCount(projectId);
        int slotWeight = concurrencyPolicy.slotWeight(tripleCount);

        DLQueryJob job = DLQueryJob.builder()
                .jobId(UUID.randomUUID().toString())
                .projectId(projectId)
                .expression(expression)
                .queryTypes(queryTypes)
                .status(DLQueryJob.Status.QUEUED)
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
        broadcastStats();
        log.info("[DLQuery] Enqueued job {} for project {} (triples={}, weight={}, position {})",
                job.getJobId(), projectId, tripleCount, slotWeight, job.getQueuePosition());
        return job;
    }

    public synchronized DLQueryJob dequeue() {
        DLQueryJob next = queue.peekFirst();
        if (next == null || !concurrencyPolicy.canStartAnother(activeJobs.values(), next)) {
            if (next != null) {
                DLQueryConcurrencyPolicy.ConcurrencySnapshot snap =
                        concurrencyPolicy.snapshot(activeJobs.values());
                log.debug("[DLQuery] Deferring job {} — effectiveMax={}, usedSlots={}, reason={}",
                        next.getJobId(), snap.effectiveMax(), snap.usedSlots(), snap.reason());
            }
            return null;
        }

        DLQueryJob job = queue.removeFirst();
        job.setStatus(DLQueryJob.Status.PROCESSING);
        job.setStartedAt(Instant.now());
        job.setQueuePosition(0);
        activeJobs.put(job.getJobId(), job);
        updateQueuePositions();
        notifyJob(job);
        broadcastStats();
        log.info("[DLQuery] Started job {} for project {} (waited {} ms, weight={})",
                job.getJobId(), job.getProjectId(), job.getWaitTimeMs(), job.getSlotWeight());
        return job;
    }

    public synchronized void markCompleted(DLQueryJob job, Map<String, Object> result, long durationMs) {
        activeJobs.remove(job.getJobId());
        job.setStatus(DLQueryJob.Status.COMPLETED);
        job.setCompletedAt(Instant.now());
        job.setExecutionTimeMs(durationMs);
        job.setResult(result);
        finishedJobs.put(job.getJobId(), job);
        recordDuration(durationMs);
        notifyJob(job);
        broadcastStats();
        log.info("[DLQuery] Completed job {} in {} ms", job.getJobId(), durationMs);
    }

    public synchronized void markFailed(DLQueryJob job, String error) {
        activeJobs.remove(job.getJobId());
        job.setStatus(DLQueryJob.Status.FAILED);
        job.setCompletedAt(Instant.now());
        job.setError(error);
        finishedJobs.put(job.getJobId(), job);
        notifyJob(job);
        broadcastStats();
        log.error("[DLQuery] Failed job {}: {}", job.getJobId(), error);
    }

    public DLQueryJob getJob(String jobId) {
        DLQueryJob active = activeJobs.get(jobId);
        if (active != null) {
            return active;
        }
        DLQueryJob queued = findInQueue(jobId);
        if (queued != null) {
            return queued;
        }
        return finishedJobs.getIfPresent(jobId);
    }

    public synchronized long getEstimatedWaitTimeMs(String jobId) {
        DLQueryJob job = getJob(jobId);
        if (job == null) {
            return 0;
        }
        return calculateEstimatedWaitTime(job);
    }

    public synchronized DLQueryJobMessage.DLQueryQueueStats getQueueStats() {
        DLQueryConcurrencyPolicy.ConcurrencySnapshot snap =
                concurrencyPolicy.snapshot(activeJobs.values());
        return DLQueryJobMessage.DLQueryQueueStats.builder()
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
        DLQueryJob next = queue.peekFirst();
        return next != null && concurrencyPolicy.canStartAnother(activeJobs.values(), next);
    }

    public synchronized boolean hasQueuedJobs() {
        return !queue.isEmpty();
    }

    private long resolveTripleCount(String projectId) {
        try {
            return datasetService.getDatasetSize(projectId);
        } catch (Exception e) {
            log.warn("[DLQuery] Could not read triple count for {}: {}", projectId, e.getMessage());
            return -1;
        }
    }

    private DLQueryJob findActiveOrQueuedJob(String projectId, String expression) {
        for (DLQueryJob job : activeJobs.values()) {
            if (projectId.equals(job.getProjectId())
                    && expression.equals(job.getExpression())
                    && job.getStatus() != DLQueryJob.Status.FAILED) {
                return job;
            }
        }
        return queue.stream()
                .filter(job -> projectId.equals(job.getProjectId()) && expression.equals(job.getExpression()))
                .findFirst()
                .orElse(null);
    }

    private DLQueryJob findInQueue(String jobId) {
        return queue.stream().filter(job -> jobId.equals(job.getJobId())).findFirst().orElse(null);
    }

    private void updateQueuePositions() {
        int position = 1;
        for (DLQueryJob job : queue) {
            job.setQueuePosition(position++);
            job.setEstimatedWaitTimeMs(calculateEstimatedWaitTime(job));
            notifyJob(job);
        }
    }

    private long calculateEstimatedWaitTime(DLQueryJob target) {
        if (target.getStatus() == DLQueryJob.Status.PROCESSING) {
            return 0;
        }
        long waitMs = activeJobs.values().stream()
                .mapToLong(this::estimateRemainingTimeMs)
                .sum();
        for (DLQueryJob job : queue) {
            if (job.getJobId().equals(target.getJobId())) {
                break;
            }
            waitMs += estimateDurationForJob(job);
        }
        return Math.min(waitMs, MAX_WAIT_TIME_MS);
    }

    private long estimateDurationForJob(DLQueryJob job) {
        long base = getAverageProcessingTimeMs();
        return (long) (base * Math.max(1, job.getSlotWeight()) * 0.75);
    }

    private long estimateRemainingTimeMs(DLQueryJob job) {
        long estimate = estimateDurationForJob(job);
        if (job.getStartedAt() == null) {
            return estimate;
        }
        long elapsed = Instant.now().toEpochMilli() - job.getStartedAt().toEpochMilli();
        return Math.max(0, estimate - elapsed);
    }

    private long getAverageProcessingTimeMs() {
        long count = completedCount.get();
        if (count == 0) {
            return DEFAULT_ESTIMATE_MS;
        }
        return totalDurationMs.get() / count;
    }

    private void recordDuration(long durationMs) {
        totalDurationMs.addAndGet(durationMs);
        completedCount.incrementAndGet();
    }

    private void notifyJob(DLQueryJob job) {
        DLQueryJobMessage message = DLQueryJobMessage.builder()
                .jobId(job.getJobId())
                .projectId(job.getProjectId())
                .status(job.getStatus().name())
                .queuePosition(job.getQueuePosition())
                .estimatedWaitTimeMs(calculateEstimatedWaitTime(job))
                .executionTimeMs(job.getExecutionTimeMs())
                .result(job.getResult())
                .error(job.getStatus() == DLQueryJob.Status.FAILED
                        ? userFriendlyError(job.getError()) : job.getError())
                .message(buildMessage(job))
                .timestamp(System.currentTimeMillis())
                .queueStats(getQueueStats())
                .build();

        try {
            messagingTemplate.convertAndSend("/topic/dlquery/" + job.getJobId(), message);
        } catch (Exception e) {
            log.warn("[DLQuery] Failed to send job notification for {}: {}", job.getJobId(), e.getMessage());
        }
    }

    private void broadcastStats() {
        DLQueryJobMessage.DLQueryQueueStats stats = getQueueStats();
        DLQueryJobMessage message = DLQueryJobMessage.builder()
                .status("QUEUE_STATS")
                .timestamp(System.currentTimeMillis())
                .queueStats(stats)
                .build();
        try {
            messagingTemplate.convertAndSend("/topic/dlquery/stats", message);
        } catch (Exception e) {
            log.warn("[DLQuery] Failed to broadcast stats: {}", e.getMessage());
        }
    }

    private String buildMessage(DLQueryJob job) {
        long waitSec = Math.max(0, calculateEstimatedWaitTime(job) / 1000);
        return switch (job.getStatus()) {
            case QUEUED -> formatQueuedMessage(job.getQueuePosition(), waitSec);
            case PROCESSING -> "Running your query…";
            case COMPLETED -> "Query complete";
            case FAILED -> userFriendlyError(job.getError());
        };
    }

    private String formatQueuedMessage(int position, long waitSec) {
        if (position <= 1) {
            return waitSec > 0
                    ? String.format("Your query is next in line (about %d sec)", waitSec)
                    : "Your query is next in line";
        }
        int ahead = Math.max(0, position - 1);
        if (waitSec > 0) {
            return String.format("Your query is queued — position %d (%d ahead, about %d sec)",
                    position, ahead, waitSec);
        }
        return String.format("Your query is queued — position %d (%d ahead)", position, ahead);
    }

    public static String userFriendlyError(String raw) {
        return self.research.ontology.common.ReasoningFriendlyErrors.forUser(raw);
    }

    public synchronized List<DLQueryJob> snapshotQueuedJobs() {
        return new ArrayList<>(queue);
    }

    Collection<DLQueryJob> activeJobsView() {
        return activeJobs.values();
    }
}
