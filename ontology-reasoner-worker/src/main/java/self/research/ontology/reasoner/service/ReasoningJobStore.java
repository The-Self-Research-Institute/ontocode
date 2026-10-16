package self.research.ontology.reasoner.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import self.research.ontology.reasoner.model.ReasoningJob;
import self.research.ontology.reasoner.model.ReasoningJobRecord;
import self.research.ontology.reasoner.repository.ReasoningJobRecordRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ReasoningJobStore implements SmartInitializingSingleton {

    static final String RESTART_MESSAGE = "Reasoning worker restarted before this job finished. Please run it again.";
    static final String RESULT_TOO_LARGE_MESSAGE =
            "This result was bigger than the worker saves, and it is no longer in memory. Please run it again.";
    private static final long OUTAGE_BACKOFF_MS = 30_000;
    private static final TypeReference<Map<String, Object>> RESULT_TYPE = new TypeReference<>() {};

    private final ReasoningJobRecordRepository repository;
    private final int maxResultBytes;
    private final long retentionHours;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ThreadPoolExecutor writer;
    private final Instant instanceStartedAt = Instant.now();
    private volatile long unavailableUntil;
    private volatile boolean outageLogged;

    @Autowired
    public ReasoningJobStore(ObjectProvider<ReasoningJobRecordRepository> repository,
                             @Value("${ontocode.reasoning.job-record-max-result-bytes:1000000}") int maxResultBytes,
                             @Value("${ontocode.reasoning.job-record-retention-hours:72}") long retentionHours) {
        this(repository.getIfAvailable(), maxResultBytes, retentionHours);
    }

    ReasoningJobStore(ReasoningJobRecordRepository repository, int maxResultBytes, long retentionHours) {
        this.repository = repository;
        this.maxResultBytes = maxResultBytes;
        this.retentionHours = retentionHours;
        this.writer = repository == null ? null : new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(1000),
                r -> {
                    Thread t = new Thread(r, "reasoning-job-store");
                    t.setDaemon(true);
                    return t;
                });
    }

    static ReasoningJobStore disabled() {
        return new ReasoningJobStore((ReasoningJobRecordRepository) null, 0, 0);
    }

    @Override
    public void afterSingletonsInstantiated() {
        recoverInterruptedJobs();
    }

    int recoverInterruptedJobs() {
        if (!available()) {
            return 0;
        }
        try {
            List<ReasoningJobRecord> unfinished = repository.findByStatusIn(List.of(
                    ReasoningJob.Status.QUEUED.name(), ReasoningJob.Status.PROCESSING.name()));
            Instant now = Instant.now();
            List<ReasoningJobRecord> interrupted = unfinished.stream()
                    .filter(record -> record.getQueuedAt() == null || record.getQueuedAt().isBefore(instanceStartedAt))
                    .toList();
            for (ReasoningJobRecord record : interrupted) {
                record.setStatus(ReasoningJob.Status.FAILED.name());
                record.setError(RESTART_MESSAGE);
                record.setCompletedAt(now);
                record.setUpdatedAt(now);
            }
            if (!interrupted.isEmpty()) {
                repository.saveAll(interrupted);
                log.info("[JobStore] Marked {} reasoning job(s) left unfinished by the previous run as failed",
                        interrupted.size());
            }
            markAvailable();
            return interrupted.size();
        } catch (Exception e) {
            markUnavailable("recover unfinished jobs", e);
            return 0;
        }
    }

    public void save(ReasoningJob job) {
        if (writer == null || job == null || job.getJobId() == null || !available()) {
            return;
        }
        ReasoningJobRecord record = baseRecord(job);
        Map<String, Object> result = job.getResult();
        try {
            writer.execute(() -> write(record, result));
        } catch (RejectedExecutionException e) {
            log.warn("[JobStore] Write queue full — not persisting {} state for job {}", record.getStatus(), record.getJobId());
        }
    }

    public Optional<ReasoningJob> find(String jobId) {
        if (repository == null || jobId == null || !available()) {
            return Optional.empty();
        }
        try {
            Optional<ReasoningJob> job = repository.findById(jobId).map(this::toJob);
            markAvailable();
            return job;
        } catch (Exception e) {
            markUnavailable("read job " + jobId, e);
            return Optional.empty();
        }
    }

    @Scheduled(fixedDelayString = "${ontocode.reasoning.job-record-purge-interval-ms:3600000}")
    public void purgeOldRecords() {
        if (repository == null || retentionHours <= 0 || !available()) {
            return;
        }
        try {
            long removed = repository.deleteByUpdatedAtBefore(Instant.now().minus(Duration.ofHours(retentionHours)));
            if (removed > 0) {
                log.info("[JobStore] Removed {} reasoning job record(s) older than {} h", removed, retentionHours);
            }
            markAvailable();
        } catch (Exception e) {
            markUnavailable("purge old job records", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (writer == null) {
            return;
        }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(2, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    ReasoningJobRecord toRecord(ReasoningJob job) {
        ReasoningJobRecord record = baseRecord(job);
        attachResult(record, job.getResult());
        return record;
    }

    private void write(ReasoningJobRecord record, Map<String, Object> result) {
        if (!available()) {
            return;
        }
        try {
            attachResult(record, result);
            repository.save(record);
            markAvailable();
        } catch (Exception e) {
            markUnavailable("save job " + record.getJobId(), e);
        }
    }

    private ReasoningJobRecord baseRecord(ReasoningJob job) {
        return ReasoningJobRecord.builder()
                .jobId(job.getJobId())
                .jobType(job.getJobType() != null ? job.getJobType().name() : null)
                .projectId(job.getProjectId())
                .status(job.getStatus() != null ? job.getStatus().name() : null)
                .queuedAt(job.getQueuedAt())
                .startedAt(job.getStartedAt())
                .completedAt(job.getCompletedAt())
                .updatedAt(Instant.now())
                .executionTimeMs(job.getExecutionTimeMs())
                .error(job.getError())
                .build();
    }

    private void attachResult(ReasoningJobRecord record, Map<String, Object> result) {
        if (result == null) {
            return;
        }
        try {
            byte[] json = objectMapper.writeValueAsBytes(result);
            if (json.length > maxResultBytes) {
                record.setResultTooLarge(true);
                return;
            }
            record.setResultJson(new String(json, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("[JobStore] Could not serialize result for job {}: {}", record.getJobId(), e.getMessage());
            record.setResultTooLarge(true);
        }
    }

    ReasoningJob toJob(ReasoningJobRecord record) {
        ReasoningJob.Status status = parseStatus(record.getStatus());
        String error = record.getError();
        Map<String, Object> result = null;
        if (record.getResultJson() != null) {
            try {
                result = objectMapper.readValue(record.getResultJson(), RESULT_TYPE);
            } catch (Exception e) {
                log.warn("[JobStore] Could not read stored result for job {}: {}", record.getJobId(), e.getMessage());
            }
        }
        if (status == ReasoningJob.Status.COMPLETED && result == null) {
            status = ReasoningJob.Status.FAILED;
            error = RESULT_TOO_LARGE_MESSAGE;
        }
        return ReasoningJob.builder()
                .jobId(record.getJobId())
                .jobType(parseJobType(record.getJobType()))
                .projectId(record.getProjectId())
                .status(status)
                .queuePosition(0)
                .queuedAt(record.getQueuedAt())
                .startedAt(record.getStartedAt())
                .completedAt(record.getCompletedAt())
                .executionTimeMs(record.getExecutionTimeMs())
                .error(error)
                .result(result)
                .build();
    }

    private static ReasoningJob.Status parseStatus(String raw) {
        try {
            return raw != null ? ReasoningJob.Status.valueOf(raw) : ReasoningJob.Status.FAILED;
        } catch (IllegalArgumentException e) {
            return ReasoningJob.Status.FAILED;
        }
    }

    private static ReasoningJob.JobType parseJobType(String raw) {
        try {
            return raw != null ? ReasoningJob.JobType.valueOf(raw) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean available() {
        return repository != null && System.currentTimeMillis() >= unavailableUntil;
    }

    private void markAvailable() {
        if (outageLogged) {
            outageLogged = false;
            log.info("[JobStore] Job persistence is working again");
        }
    }

    private void markUnavailable(String action, Exception e) {
        unavailableUntil = System.currentTimeMillis() + OUTAGE_BACKOFF_MS;
        if (!outageLogged) {
            outageLogged = true;
            log.warn("[JobStore] Could not {} in MongoDB — keeping jobs in memory only for now: {}", action, e.getMessage());
        }
    }
}
