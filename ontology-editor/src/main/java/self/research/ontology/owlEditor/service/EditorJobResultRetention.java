package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import self.research.ontology.common.ReasoningHeapMonitor;
import self.research.ontology.owlEditor.model.DLQueryJob;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Heap-aware LRU for worker job results relayed through the editor (DL Query / async reasoner).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ontocode.reasoner-worker.enabled", havingValue = "true")
public class EditorJobResultRetention {

    @Value("${ontocode.reasoning.heap-comfort-used-ratio:0.40}")
    private double heapComfortUsedRatio;

    @Value("${ontocode.reasoning.heap-pressure-used-ratio:0.70}")
    private double heapPressureUsedRatio;

    @Value("${ontocode.reasoning.max-retained-results:8}")
    private int maxRetainedResults;

    @Value("${ontocode.reasoning.idle-eviction-minutes:15}")
    private int idleEvictionMinutes;

    private final LinkedHashMap<String, DLQueryJob> jobsByFinishOrder = new LinkedHashMap<>();

    public synchronized void retain(DLQueryJob job) {
        if (job == null || job.getJobId() == null) {
            return;
        }
        jobsByFinishOrder.put(job.getJobId(), job);
    }

    public synchronized Optional<DLQueryJob> get(String jobId) {
        DLQueryJob job = jobsByFinishOrder.remove(jobId);
        if (job != null) {
            jobsByFinishOrder.put(jobId, job);
        }
        return Optional.ofNullable(job);
    }

    public synchronized void makeRoomForIncomingJob() {
        if (isHeapComfortable() && jobsByFinishOrder.size() < maxRetainedResults) {
            return;
        }
        while (needsEviction()) {
            if (!evictOldest()) {
                break;
            }
        }
    }

    @Scheduled(fixedDelayString = "${ontocode.reasoner.cache-janitor-interval-ms:60000}")
    public synchronized void evictStaleIdleEntries() {
        if (isHeapComfortable() && jobsByFinishOrder.size() <= maxRetainedResults) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(Math.max(1, idleEvictionMinutes)));
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, DLQueryJob> e : jobsByFinishOrder.entrySet()) {
            DLQueryJob job = e.getValue();
            Instant completed = job.getCompletedAt() != null ? job.getCompletedAt() : job.getQueuedAt();
            if (completed != null && completed.isBefore(cutoff)) {
                stale.add(e.getKey());
            }
        }
        for (String jobId : stale) {
            if (!needsEviction()) {
                break;
            }
            jobsByFinishOrder.remove(jobId);
        }
    }

    private boolean needsEviction() {
        return jobsByFinishOrder.size() > maxRetainedResults || isHeapUnderPressure();
    }

    private boolean isHeapComfortable() {
        return ReasoningHeapMonitor.usedRatio() < heapComfortUsedRatio;
    }

    private boolean isHeapUnderPressure() {
        return ReasoningHeapMonitor.usedRatio() >= heapPressureUsedRatio;
    }

    private boolean evictOldest() {
        var it = jobsByFinishOrder.entrySet().iterator();
        if (!it.hasNext()) {
            return false;
        }
        String oldest = it.next().getKey();
        jobsByFinishOrder.remove(oldest);
        log.info("[EditorJobRetention] Evicted oldest relay job {}", oldest);
        return true;
    }
}
