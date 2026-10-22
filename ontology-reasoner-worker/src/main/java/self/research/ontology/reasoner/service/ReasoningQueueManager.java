package self.research.ontology.reasoner.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class ReasoningQueueManager {

    private final EditorClient editorClient;
    private final ReasoningConcurrencyPolicy concurrencyPolicy;
    private final ReasoningResultRetention resultRetention;
    private final ReasoningJobStore jobStore;
    private final LinkedList<ReasoningJob> queue = new LinkedList<>();
    private final Map<String, ReasoningJob> activeJobs = new ConcurrentHashMap<>();
    private final Map<String, ReasoningJob> liveJobs = new ConcurrentHashMap<>();
    private final AtomicLong totalDurationMs = new AtomicLong(0);
    private final AtomicLong completedCount = new AtomicLong(0);
    private volatile Instant lastActivityAt = Instant.now();
    private final ReasoningJobEventSender eventSender;
    private final ReasoningSnapshotService snapshotService;

    private static final long DEFAULT_ESTIMATE_MS = 120_000;
    private static final long MAX_WAIT_TIME_MS = 30 * 60 * 1000;
    static final String CANCELLED_MESSAGE = "Cancelled";

    @Value("${ontocode.reasoning.max-queued-per-project:5}")
    private int maxQueuedPerProject = 5;

    public enum CancelOutcome { NOT_FOUND, CANCELLED_QUEUED, CANCELLED_RUNNING, ALREADY_FINISHED, STILL_REQUESTED }

    public record CancelResult(CancelOutcome outcome, ReasoningJob job) {}

    public static class ProjectQueueFullException extends RuntimeException {
        public ProjectQueueFullException(String message) {
            super(message);
        }
    }

    @Autowired
    public ReasoningQueueManager(EditorClient editorClient,
                                 ReasoningConcurrencyPolicy concurrencyPolicy,
                                 ReasoningResultRetention resultRetention,
                                 ReasoningJobStore jobStore,
                                 ReasoningSnapshotService snapshotService) {
        this(editorClient, concurrencyPolicy, resultRetention, jobStore,
                new ReasoningJobEventSender(editorClient::publishJobEvent, 1000), snapshotService);
    }

    ReasoningQueueManager(EditorClient editorClient,
                          ReasoningConcurrencyPolicy concurrencyPolicy,
                          ReasoningResultRetention resultRetention,
                          ReasoningJobStore jobStore) {
        this(editorClient, concurrencyPolicy, resultRetention, jobStore,
                new ReasoningJobEventSender(editorClient::publishJobEvent, 1000), null);
    }

    ReasoningQueueManager(EditorClient editorClient,
                          ReasoningConcurrencyPolicy concurrencyPolicy,
                          ReasoningResultRetention resultRetention,
                          ReasoningJobStore jobStore,
                          ReasoningJobEventSender eventSender) {
        this(editorClient, concurrencyPolicy, resultRetention, jobStore, eventSender, null);
    }

    ReasoningQueueManager(EditorClient editorClient,
                          ReasoningConcurrencyPolicy concurrencyPolicy,
                          ReasoningResultRetention resultRetention,
                          ReasoningJobStore jobStore,
                          ReasoningJobEventSender eventSender,
                          ReasoningSnapshotService snapshotService) {
        this.editorClient = editorClient;
        this.concurrencyPolicy = concurrencyPolicy;
        this.resultRetention = resultRetention;
        this.jobStore = jobStore;
        this.eventSender = eventSender;
        this.snapshotService = snapshotService;
    }

    @PreDestroy
    public void shutdown() {
        eventSender.shutdown(2_000);
    }

    public ReasoningJob enqueue(ReasoningJob.JobType jobType,
                                String projectId,
                                String expression,
                                List<String> queryTypes,
                                String reasonerType,
                                String ownerEmail) {
        return enqueue(jobType, projectId, expression, queryTypes, reasonerType, ownerEmail, null, null);
    }

    public ReasoningJob enqueue(ReasoningJob.JobType jobType,
                                String projectId,
                                String expression,
                                List<String> queryTypes,
                                String reasonerType,
                                String ownerEmail,
                                Integer maxJustifications,
                                String explanationMode) {
        return enqueue(jobType, projectId, expression, queryTypes, reasonerType, ownerEmail,
                maxJustifications, explanationMode, null, null, null, null, null);
    }

    public ReasoningJob enqueue(ReasoningJob.JobType jobType,
                                String projectId,
                                String expression,
                                List<String> queryTypes,
                                String reasonerType,
                                String ownerEmail,
                                Integer maxJustifications,
                                String explanationMode,
                                String entailmentType,
                                String subjectIri,
                                String targetIri,
                                String propertyIri,
                                String literalValue) {
        long tripleCount = editorClient.getTripleCount(projectId);
        ReasoningJob answered = answerFromCachedSnapshot(jobType, projectId, reasonerType, ownerEmail, tripleCount);
        if (answered != null) {
            return answered;
        }
        int slotWeight = concurrencyPolicy.slotWeight(tripleCount);

        ReasoningJob job;
        synchronized (this) {
            ReasoningJob duplicate = findInFlightDuplicate(jobType, projectId, expression, queryTypes,
                    reasonerType, ownerEmail, maxJustifications, explanationMode,
                    entailmentType, subjectIri, targetIri, propertyIri, literalValue);
            if (duplicate != null) {
                duplicate.setRequesterCount(duplicate.getRequesterCount() + 1);
                log.info("[Reasoning] Reusing in-flight {} job {} for project {} instead of enqueueing a duplicate ({} requesters)",
                        jobType, duplicate.getJobId(), projectId, duplicate.getRequesterCount());
                return duplicate;
            }

            if (maxQueuedPerProject > 0 && queuedJobCount(projectId) >= maxQueuedPerProject) {
                throw new ProjectQueueFullException("This project already has " + maxQueuedPerProject
                        + " reasoning jobs waiting to run. Wait for one of them to finish, then try again.");
            }

            resultRetention.makeRoomForIncomingJob();

            job = ReasoningJob.builder()
                    .jobId(UUID.randomUUID().toString())
                    .jobType(jobType)
                    .projectId(projectId)
                    .expression(expression)
                    .queryTypes(queryTypes)
                    .reasonerType(reasonerType)
                    .maxJustifications(maxJustifications)
                    .explanationMode(explanationMode)
                    .entailmentType(entailmentType)
                    .subjectIri(subjectIri)
                    .targetIri(targetIri)
                    .propertyIri(propertyIri)
                    .literalValue(literalValue)
                    .status(ReasoningJob.Status.QUEUED)
                    .queuePosition(queue.size() + 1)
                    .estimatedWaitTimeMs(0)
                    .queuedAt(Instant.now())
                    .ownerEmail(ownerEmail)
                    .tripleCount(tripleCount)
                    .slotWeight(slotWeight)
                    .build();

            queue.addLast(job);
            liveJobs.put(job.getJobId(), job);
            updateQueuePositions();
            notifyJob(job);
            jobStore.save(job);
            touchActivity();
        }
        log.info("[Reasoning] Enqueued {} job {} for project {} (position {})",
                jobType, job.getJobId(), projectId, job.getQueuePosition());

        log.info("[DIAG] enqueue: jobId={} jobType={} projectId={} thread={} identityHash={} atMs={}",
                job.getJobId(), jobType, projectId, Thread.currentThread().getName(),
                System.identityHashCode(job), System.currentTimeMillis());
        return job;
    }

    private ReasoningJob answerFromCachedSnapshot(ReasoningJob.JobType jobType, String projectId,
                                                  String reasonerType, String ownerEmail, long tripleCount) {
        if (jobType != ReasoningJob.JobType.REASONER_SNAPSHOT || snapshotService == null || ownerKey(ownerEmail) != null) {
            return null;
        }
        Optional<Map<String, Object>> cached;
        try {
            cached = snapshotService.cached(projectId, reasonerType, tripleCount);
        } catch (Exception e) {
            log.warn("[Reasoning] Could not look up a cached snapshot for project {}: {}", projectId, e.getMessage());
            return null;
        }
        if (cached.isEmpty()) {
            return null;
        }
        ReasoningJob job;
        synchronized (this) {
            resultRetention.makeRoomForIncomingJob();
            Instant now = Instant.now();
            job = ReasoningJob.builder()
                    .jobId(UUID.randomUUID().toString())
                    .jobType(jobType)
                    .projectId(projectId)
                    .reasonerType(reasonerType)
                    .status(ReasoningJob.Status.COMPLETED)
                    .queuePosition(0)
                    .estimatedWaitTimeMs(0)
                    .queuedAt(now)
                    .startedAt(now)
                    .completedAt(now)
                    .executionTimeMs(0)
                    .result(cached.get())
                    .ownerEmail(ownerEmail)
                    .tripleCount(tripleCount)
                    .slotWeight(0)
                    .build();
            resultRetention.retain(job);
            notifyJob(job);
            jobStore.save(job);
            touchActivity();
        }
        log.info("[Reasoning] Answered {} job {} for project {} from the cached snapshot without queueing it",
                jobType, job.getJobId(), projectId);
        return job;
    }

    private int queuedJobCount(String projectId) {
        int count = 0;
        for (ReasoningJob queued : queue) {
            if (Objects.equals(queued.getProjectId(), projectId)) {
                count++;
            }
        }
        return count;
    }

    private ReasoningJob findInFlightDuplicate(ReasoningJob.JobType jobType, String projectId, String expression,
                                                List<String> queryTypes, String reasonerType, String ownerEmail,
                                                Integer maxJustifications, String explanationMode,
                                                String entailmentType, String subjectIri, String targetIri,
                                                String propertyIri, String literalValue) {
        for (ReasoningJob candidate : queue) {
            if (candidate.getStatus() == ReasoningJob.Status.QUEUED
                    && isSameRequest(candidate, jobType, projectId, expression, queryTypes, reasonerType,
                    ownerEmail, maxJustifications, explanationMode,
                    entailmentType, subjectIri, targetIri, propertyIri, literalValue)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isSameRequest(ReasoningJob candidate, ReasoningJob.JobType jobType, String projectId,
                                   String expression, List<String> queryTypes, String reasonerType,
                                   String ownerEmail, Integer maxJustifications, String explanationMode,
                                   String entailmentType, String subjectIri, String targetIri,
                                   String propertyIri, String literalValue) {
        return Objects.equals(candidate.getJobType(), jobType)
                && Objects.equals(candidate.getProjectId(), projectId)
                && Objects.equals(candidate.getExpression(), expression)
                && Objects.equals(candidate.getQueryTypes(), queryTypes)
                && Objects.equals(candidate.getReasonerType(), reasonerType)
                && Objects.equals(ownerKey(candidate.getOwnerEmail()), ownerKey(ownerEmail))
                && Objects.equals(candidate.getMaxJustifications(), maxJustifications)
                && Objects.equals(candidate.getExplanationMode(), explanationMode)
                && Objects.equals(candidate.getEntailmentType(), entailmentType)
                && Objects.equals(candidate.getSubjectIri(), subjectIri)
                && Objects.equals(candidate.getTargetIri(), targetIri)
                && Objects.equals(candidate.getPropertyIri(), propertyIri)
                && Objects.equals(candidate.getLiteralValue(), literalValue);
    }

    private static String ownerKey(String ownerEmail) {
        return ownerEmail == null || ownerEmail.isBlank() ? null : ownerEmail;
    }

    public synchronized ReasoningJob dequeue() {

        ReasoningJob candidate = findFirstAdmissible();
        if (candidate == null) {
            return null;
        }

        queue.remove(candidate);
        candidate.setStatus(ReasoningJob.Status.PROCESSING);
        candidate.setStartedAt(Instant.now());
        candidate.setQueuePosition(0);
        activeJobs.put(candidate.getJobId(), candidate);
        updateQueuePositions();
        notifyJob(candidate);
        jobStore.save(candidate);
        touchActivity();
        return candidate;
    }

    private ReasoningJob findFirstAdmissible() {
        for (ReasoningJob job : queue) {
            if (concurrencyPolicy.canStartAnother(activeJobs.values(), job)) {
                return job;
            }
        }
        return null;
    }

    public synchronized void markCompleted(ReasoningJob job, Map<String, Object> result, long durationMs) {
        log.info("[DIAG] markCompleted: jobId={} jobType={} identityHash={} atMs={}",
                job.getJobId(), job.getJobType(), System.identityHashCode(job), System.currentTimeMillis());
        if (isFinished(job)) {
            releaseFinishedJob(job);
            return;
        }
        job.setStatus(ReasoningJob.Status.COMPLETED);
        job.setCompletedAt(Instant.now());
        job.setExecutionTimeMs(durationMs);
        job.setResult(result);
        resultRetention.retain(job);
        liveJobs.remove(job.getJobId());
        activeJobs.remove(job.getJobId());
        recordDuration(durationMs);
        notifyJob(job);
        jobStore.save(job);
        touchActivity();
    }

    public synchronized void markFailed(ReasoningJob job, String error) {
        if (isFinished(job)) {
            releaseFinishedJob(job);
            return;
        }
        job.setStatus(ReasoningJob.Status.FAILED);
        job.setCompletedAt(Instant.now());
        job.setError(error);
        resultRetention.retain(job);
        liveJobs.remove(job.getJobId());
        activeJobs.remove(job.getJobId());
        notifyJob(job);
        jobStore.save(job);
        touchActivity();
        log.error("[Reasoning] Failed job {}: {}", job.getJobId(), error);
    }

    public synchronized void reportTimedOut(ReasoningJob job, String error) {
        if (isFinished(job)) {
            return;
        }
        job.setStatus(ReasoningJob.Status.FAILED);
        job.setCompletedAt(Instant.now());
        job.setError(error);
        resultRetention.retain(job);
        notifyJob(job);
        jobStore.save(job);
        touchActivity();
        log.error("[Reasoning] Timed out job {}: {}", job.getJobId(), error);
    }

    public synchronized void releaseAbandonedSlot(String jobId) {
        activeJobs.remove(jobId);
        liveJobs.remove(jobId);
    }

    public CancelResult cancel(String jobId) {
        CancelResult result = cancelInMemory(jobId);
        if (result.outcome() != CancelOutcome.NOT_FOUND) {
            return result;
        }
        return jobStore.find(jobId)
                .map(stored -> new CancelResult(CancelOutcome.ALREADY_FINISHED, stored))
                .orElse(result);
    }

    private synchronized CancelResult cancelInMemory(String jobId) {
        ReasoningJob job = liveJobs.get(jobId);
        if (job == null) {
            ReasoningJob finished = resultRetention.get(jobId).orElse(null);
            return finished != null
                    ? new CancelResult(CancelOutcome.ALREADY_FINISHED, finished)
                    : new CancelResult(CancelOutcome.NOT_FOUND, null);
        }
        if (!isFinished(job) && job.getRequesterCount() > 1) {
            job.setRequesterCount(job.getRequesterCount() - 1);
            log.info("[Reasoning] Kept job {} running — {} other requester(s) still waiting for it",
                    jobId, job.getRequesterCount());
            return new CancelResult(CancelOutcome.STILL_REQUESTED, job);
        }
        if (job.getStatus() == ReasoningJob.Status.QUEUED) {
            queue.remove(job);
            finishAsCancelled(job);
            liveJobs.remove(jobId);
            updateQueuePositions();
            log.info("[Reasoning] Cancelled queued job {}", jobId);
            return new CancelResult(CancelOutcome.CANCELLED_QUEUED, job);
        }
        if (job.getStatus() == ReasoningJob.Status.PROCESSING) {
            finishAsCancelled(job);
            log.info("[Reasoning] Cancelled running job {} — its slot is released once the thread stops", jobId);
            return new CancelResult(CancelOutcome.CANCELLED_RUNNING, job);
        }
        return new CancelResult(CancelOutcome.ALREADY_FINISHED, job);
    }

    private void finishAsCancelled(ReasoningJob job) {
        job.setStatus(ReasoningJob.Status.FAILED);
        job.setCompletedAt(Instant.now());
        job.setError(CANCELLED_MESSAGE);
        job.setQueuePosition(0);
        resultRetention.retain(job);
        notifyJob(job);
        jobStore.save(job);
        touchActivity();
    }

    public synchronized boolean isStillProcessing(ReasoningJob job) {
        return job.getStatus() == ReasoningJob.Status.PROCESSING;
    }

    private static boolean isFinished(ReasoningJob job) {
        return job.getStatus() == ReasoningJob.Status.COMPLETED || job.getStatus() == ReasoningJob.Status.FAILED;
    }

    private void releaseFinishedJob(ReasoningJob job) {
        liveJobs.remove(job.getJobId());
        activeJobs.remove(job.getJobId());
        touchActivity();
    }

    public ReasoningJob getJob(String jobId) {
        ReasoningJob live = liveJobs.get(jobId);
        if (live != null) {
            log.info("[DIAG] getJob: jobId={} source=live status={} jobType={} identityHash={} atMs={}",
                    jobId, live.getStatus(), live.getJobType(), System.identityHashCode(live), System.currentTimeMillis());
            return live;
        }
        ReasoningJob retained = resultRetention.get(jobId).orElse(null);
        if (retained != null) {
            log.info("[DIAG] getJob: jobId={} source=retention jobType={} identityHash={} atMs={}",
                    jobId, retained.getJobType(), System.identityHashCode(retained), System.currentTimeMillis());
            return retained;
        }
        ReasoningJob stored = jobStore.find(jobId).orElse(null);
        log.info("[DIAG] getJob: jobId={} source=store status={} atMs={}",
                jobId, stored != null ? stored.getStatus() : "NOT_FOUND", System.currentTimeMillis());
        return stored;
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

    public synchronized long getEstimatedWaitTimeMs(ReasoningJob job) {
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
        return findFirstAdmissible() != null;
    }

    public synchronized boolean hasQueuedJobs() {
        return !queue.isEmpty();
    }

    private void updateQueuePositions() {
        int position = 1;
        for (ReasoningJob job : queue) {
            int current = position++;
            if (job.getQueuePosition() != current) {
                job.setQueuePosition(current);
                job.setEstimatedWaitTimeMs(calculateEstimatedWaitTime(job));
                notifyJob(job);
            }
        }
    }

    private long calculateEstimatedWaitTime(ReasoningJob target) {
        if (target.getStatus() != ReasoningJob.Status.QUEUED) {
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
        eventSender.submit(ReasoningJobNotifier.fromEvent(event));
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
