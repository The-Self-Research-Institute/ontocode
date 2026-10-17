package self.research.ontology.reasoner.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import self.research.ontology.reasoner.model.ReasoningSnapshotRecord;
import self.research.ontology.reasoner.repository.ReasoningSnapshotRecordRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ReasoningSnapshotStore {

    private static final long OUTAGE_BACKOFF_MS = 30_000;
    private static final TypeReference<LinkedHashMap<String, Object>> SNAPSHOT_TYPE = new TypeReference<>() {};

    private final ReasoningSnapshotRecordRepository repository;
    private final int maxResultBytes;
    private final long retentionHours;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ThreadPoolExecutor writer;
    private volatile long unavailableUntil;
    private volatile boolean outageLogged;

    @Autowired
    public ReasoningSnapshotStore(ObjectProvider<ReasoningSnapshotRecordRepository> repository,
                                  @Value("${ontocode.reasoning.job-record-max-result-bytes:1000000}") int maxResultBytes,
                                  @Value("${ontocode.reasoning.job-record-retention-hours:72}") long retentionHours) {
        this(repository.getIfAvailable(), maxResultBytes, retentionHours);
    }

    ReasoningSnapshotStore(ReasoningSnapshotRecordRepository repository, int maxResultBytes, long retentionHours) {
        this.repository = repository;
        this.maxResultBytes = maxResultBytes;
        this.retentionHours = retentionHours;
        this.writer = repository == null ? null : new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "reasoning-snapshot-store");
                    t.setDaemon(true);
                    return t;
                });
    }

    static ReasoningSnapshotStore disabled() {
        return new ReasoningSnapshotStore((ReasoningSnapshotRecordRepository) null, 0, 0);
    }

    static String id(String projectId, long revision, long tripleCount, String reasoner) {
        return projectId + "|" + revision + "|" + tripleCount + "|" + reasoner;
    }

    private static boolean cacheable(long revision, long tripleCount) {
        return revision > 0 && tripleCount >= 0;
    }

    public void save(String projectId, long revision, long tripleCount, String reasoner, Map<String, Object> snapshot) {
        if (writer == null || snapshot == null || !cacheable(revision, tripleCount) || !available()) {
            return;
        }
        try {
            writer.execute(() -> write(projectId, revision, tripleCount, reasoner, snapshot));
        } catch (RejectedExecutionException e) {
            log.warn("[SnapshotStore] Write queue full — not saving the {} snapshot of project {} revision {}",
                    reasoner, projectId, revision);
        }
    }

    public Optional<Map<String, Object>> find(String projectId, long revision, long tripleCount, String reasoner) {
        if (repository == null || !cacheable(revision, tripleCount) || !available()) {
            return Optional.empty();
        }
        try {
            Optional<ReasoningSnapshotRecord> record = repository.findById(id(projectId, revision, tripleCount, reasoner));
            markAvailable();
            return record.filter(this::isFresh).flatMap(this::toSnapshot);
        } catch (Exception e) {
            markUnavailable("read the " + reasoner + " snapshot of project " + projectId, e);
            return Optional.empty();
        }
    }

    @Scheduled(fixedDelayString = "${ontocode.reasoning.job-record-purge-interval-ms:3600000}")
    public void purgeOldSnapshots() {
        if (repository == null || retentionHours <= 0 || !available()) {
            return;
        }
        try {
            long removed = repository.deleteByUpdatedAtBefore(Instant.now().minus(Duration.ofHours(retentionHours)));
            if (removed > 0) {
                log.info("[SnapshotStore] Removed {} saved snapshot(s) older than {} h", removed, retentionHours);
            }
            markAvailable();
        } catch (Exception e) {
            markUnavailable("purge old snapshots", e);
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

    Optional<ReasoningSnapshotRecord> toRecord(String projectId, long revision, long tripleCount, String reasoner,
                                               Map<String, Object> snapshot) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(snapshot);
            if (json.length > maxResultBytes) {
                log.info("[SnapshotStore] Not saving the {} snapshot of project {} revision {} — {} bytes is over the {} byte limit",
                        reasoner, projectId, revision, json.length, maxResultBytes);
                return Optional.empty();
            }
            Instant now = Instant.now();
            return Optional.of(ReasoningSnapshotRecord.builder()
                    .id(id(projectId, revision, tripleCount, reasoner))
                    .projectId(projectId)
                    .revision(revision)
                    .tripleCount(tripleCount)
                    .reasoner(reasoner)
                    .resultJson(new String(json, StandardCharsets.UTF_8))
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        } catch (Exception e) {
            log.warn("[SnapshotStore] Could not serialize the {} snapshot of project {}: {}", reasoner, projectId, e.getMessage());
            return Optional.empty();
        }
    }

    private void write(String projectId, long revision, long tripleCount, String reasoner, Map<String, Object> snapshot) {
        if (!available()) {
            return;
        }
        Optional<ReasoningSnapshotRecord> record = toRecord(projectId, revision, tripleCount, reasoner, snapshot);
        if (record.isEmpty()) {
            return;
        }
        try {
            repository.save(record.get());
            repository.deleteByProjectIdAndRevisionLessThan(projectId, revision);
            markAvailable();
        } catch (Exception e) {
            markUnavailable("save the " + reasoner + " snapshot of project " + projectId, e);
        }
    }

    private boolean isFresh(ReasoningSnapshotRecord record) {
        if (retentionHours <= 0 || record.getUpdatedAt() == null) {
            return true;
        }
        return record.getUpdatedAt().isAfter(Instant.now().minus(Duration.ofHours(retentionHours)));
    }

    private Optional<Map<String, Object>> toSnapshot(ReasoningSnapshotRecord record) {
        if (record.getResultJson() == null) {
            return Optional.empty();
        }
        try {
            Map<String, Object> snapshot = objectMapper.readValue(record.getResultJson(), SNAPSHOT_TYPE);
            snapshot.put("ontologyRevision", record.getRevision());
            return Optional.of(snapshot);
        } catch (Exception e) {
            log.warn("[SnapshotStore] Could not read saved snapshot {}: {}", record.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private boolean available() {
        return repository != null && System.currentTimeMillis() >= unavailableUntil;
    }

    private void markAvailable() {
        if (outageLogged) {
            outageLogged = false;
            log.info("[SnapshotStore] Snapshot persistence is working again");
        }
    }

    private void markUnavailable(String action, Exception e) {
        unavailableUntil = System.currentTimeMillis() + OUTAGE_BACKOFF_MS;
        if (!outageLogged) {
            outageLogged = true;
            log.warn("[SnapshotStore] Could not {} in MongoDB — keeping snapshots in memory only for now: {}",
                    action, e.getMessage());
        }
    }
}
