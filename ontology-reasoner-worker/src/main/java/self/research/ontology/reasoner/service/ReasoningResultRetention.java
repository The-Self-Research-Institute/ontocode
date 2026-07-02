package self.research.ontology.reasoner.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.common.ReasoningHeapMonitor;
import self.research.ontology.reasoner.model.ReasoningJob;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LRU retention for finished reasoning jobs (oldest finished evicted first — A before B).
 * <p>
 * Policy:
 * <ul>
 *   <li>Heap comfortable (e.g. &lt; 40% used): keep results after jobs finish — no eviction.</li>
 *   <li>New job arrives (user D): evict oldest finished results only if over capacity or heap pressure.</li>
 *   <li>15 min idle: evict per-entry only when heap is under pressure or over capacity.</li>
 * </ul>
 * Active OWL/reasoner memory is still disposed per job; this retains lightweight result maps for polling.
 */
@Slf4j
@Service
public class ReasoningResultRetention {

    /** Below this heap-used ratio, never evict for pressure alone. */
    @Value("${ontocode.reasoning.heap-comfort-used-ratio:0.40}")
    private double heapComfortUsedRatio;

    /** At or above this heap-used ratio, evict oldest when making room. */
    @Value("${ontocode.reasoning.heap-pressure-used-ratio:0.70}")
    private double heapPressureUsedRatio;

    @Value("${ontocode.reasoning.max-retained-results:8}")
    private int maxRetainedResults;

    @Value("${ontocode.reasoning.idle-eviction-minutes:15}")
    private int idleEvictionMinutes;

    /** jobId → job, insertion order = finish order (oldest first). */
    private final LinkedHashMap<String, ReasoningJob> finishedByFinishOrder = new LinkedHashMap<>();

    public ReasoningResultRetention() {
    }

    public synchronized void retain(ReasoningJob job) {
        if (job == null || job.getJobId() == null) {
            return;
        }
        finishedByFinishOrder.put(job.getJobId(), job);
        log.debug("[Retention] Retained job {} ({} total, heap {}% used)",
                job.getJobId(), finishedByFinishOrder.size(), Math.round(ReasoningHeapMonitor.usedRatio() * 100));
    }

    public synchronized Optional<ReasoningJob> get(String jobId) {
        ReasoningJob job = finishedByFinishOrder.get(jobId);
        if (job != null) {
            // Move to end on poll so actively polled results stay longer than abandoned ones
            finishedByFinishOrder.remove(jobId);
            finishedByFinishOrder.put(jobId, job);
        }
        return Optional.ofNullable(job);
    }

    /**
     * Called when a new job is enqueued (e.g. user D). Evict oldest finished (A) before newer (B)
     * only if retention cap or heap pressure requires it.
     */
    public synchronized int makeRoomForIncomingJob() {
        if (isHeapComfortable() && finishedByFinishOrder.size() < maxRetainedResults) {
            return 0;
        }
        int evicted = 0;
        while (needsEviction()) {
            if (!evictOldest()) {
                break;
            }
            evicted++;
        }
        if (evicted > 0) {
            log.info("[Retention] Freed {} oldest result(s) for incoming job (heap {}% used, {} retained)",
                    evicted, Math.round(ReasoningHeapMonitor.usedRatio() * 100), finishedByFinishOrder.size());
        }
        return evicted;
    }

    /**
     * Janitor: drop entries idle longer than configured minutes, oldest first,
     * only when heap is not comfortable or over capacity.
     */
    public synchronized int evictStaleIdleEntries() {
        if (isHeapComfortable() && finishedByFinishOrder.size() <= maxRetainedResults) {
            return 0;
        }

        Instant cutoff = Instant.now().minus(Duration.ofMinutes(Math.max(1, idleEvictionMinutes)));
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, ReasoningJob> entry : finishedByFinishOrder.entrySet()) {
            ReasoningJob job = entry.getValue();
            Instant completed = job.getCompletedAt() != null ? job.getCompletedAt() : job.getQueuedAt();
            if (completed != null && completed.isBefore(cutoff)) {
                stale.add(entry.getKey());
            }
        }

        int evicted = 0;
        for (String jobId : stale) {
            if (!needsEviction()) {
                break;
            }
            if (evict(jobId, "idle")) {
                evicted++;
            }
        }
        return evicted;
    }

    public synchronized int size() {
        return finishedByFinishOrder.size();
    }

    public synchronized void clearAll() {
        int n = finishedByFinishOrder.size();
        finishedByFinishOrder.clear();
        if (n > 0) {
            log.info("[Retention] Cleared all {} retained result(s)", n);
        }
    }

    private boolean needsEviction() {
        return finishedByFinishOrder.size() > maxRetainedResults || isHeapUnderPressure();
    }

    private boolean isHeapComfortable() {
        return ReasoningHeapMonitor.usedRatio() < heapComfortUsedRatio;
    }

    private boolean isHeapUnderPressure() {
        return ReasoningHeapMonitor.usedRatio() >= heapPressureUsedRatio;
    }

    private boolean evictOldest() {
        var it = finishedByFinishOrder.entrySet().iterator();
        if (!it.hasNext()) {
            return false;
        }
        String oldestId = it.next().getKey();
        return evict(oldestId, "lru");
    }

    private boolean evict(String jobId, String reason) {
        ReasoningJob removed = finishedByFinishOrder.remove(jobId);
        if (removed != null) {
            log.info("[Retention] Evicted {} ({}) — project {}, heap {}% used",
                    jobId, reason, removed.getProjectId(), Math.round(ReasoningHeapMonitor.usedRatio() * 100));
            return true;
        }
        return false;
    }
}
