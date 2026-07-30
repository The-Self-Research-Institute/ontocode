package self.research.ontology.reasoner.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.reasoner.model.ReasoningJob;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Collection;

@Slf4j
@Service
public class ReasoningConcurrencyPolicy {

    private final EditorClient editorClient;

    @Value("${ontocode.reasoning.max-concurrent:2}")
    private int configuredMax;

    @Value("${ontocode.reasoning.memory-aware:true}")
    private boolean memoryAware;

    @Value("${ontocode.reasoning.min-free-heap-ratio:0.20}")
    private double minFreeHeapRatio;

    @Value("${ontocode.reasoning.large-triple-threshold:500000}")
    private long largeTripleThreshold;

    @Value("${ontocode.reasoning.slot-budget:2}")
    private int slotBudget;

    @Value("${ontocode.reasoning.yield-to-imports:true}")
    private boolean yieldToImports;

    // Two independent lanes so one heavy job (e.g. a 200MB ontology) can never block cheap,
    // fast requests (e.g. a 10MB ontology) — they used to share one FIFO queue and one
    // concurrency ceiling, so a heavy job at the front of the queue starved everything
    // behind it regardless of cost. A job's lane is derived from its slotWeight: weight 1
    // (small/cheap, <=100k triples per slotWeight()) is "fast", anything heavier is "heavy".
    @Value("${ontocode.reasoning.fast-lane-max-concurrent:3}")
    private int fastLaneMaxConcurrent;

    @Value("${ontocode.reasoning.heavy-lane-max-concurrent:1}")
    private int heavyLaneMaxConcurrent;

    public ReasoningConcurrencyPolicy(EditorClient editorClient) {
        this.editorClient = editorClient;
    }

    public boolean isHeavy(ReasoningJob job) {
        return job != null && job.getSlotWeight() > 1;
    }

    public record ConcurrencySnapshot(
            int configuredMax,
            int effectiveMax,
            int slotBudget,
            int usedSlots,
            double freeHeapRatio,
            boolean memoryPressure,
            boolean importActive,
            String reason
    ) {}

    public int slotWeight(long tripleCount) {
        if (tripleCount < 0) {
            // EditorClient returns -1 when it couldn't determine the size (lookup failed).
            // Unknown cost must default to heavy, not cheap — defaulting to the fast lane
            // here would let a genuinely huge, unmeasured ontology run concurrently
            // alongside other fast jobs with none of the lane's safety assumptions holding.
            return Math.max(1, slotBudget);
        }
        if (tripleCount == 0) {
            return 1;
        }
        if (tripleCount <= 100_000) {
            return 1;
        }
        if (tripleCount < largeTripleThreshold) {
            return 2;
        }
        return Math.max(1, slotBudget);
    }

    public int usedSlots(Collection<ReasoningJob> activeJobs) {
        return activeJobs.stream().mapToInt(ReasoningJob::getSlotWeight).sum();
    }

    public ConcurrencySnapshot snapshot(Collection<ReasoningJob> activeJobs) {
        int configured = Math.max(1, configuredMax);
        int budget = Math.max(1, slotBudget);
        double freeRatio = freeHeapRatio();
        boolean importActive = yieldToImports && editorClient.getActiveImportCount() > 0;
        boolean memoryPressure = memoryAware && freeRatio < minFreeHeapRatio;

        int effective = configured;
        String reason = "configured ceiling";
        if (importActive) {
            effective = Math.min(effective, 1);
            reason = "import in progress";
        }
        if (memoryAware) {
            int heapCap = maxJobsFromHeap(activeJobs, freeRatio);
            if (heapCap < effective) {
                effective = heapCap;
                reason = memoryPressure ? "low free heap" : "heap headroom";
            }
        }

        return new ConcurrencySnapshot(
                configured,
                Math.max(0, effective),
                budget,
                usedSlots(activeJobs),
                freeRatio,
                memoryPressure,
                importActive,
                reason
        );
    }

    public boolean canStartAnother(Collection<ReasoningJob> activeJobs, ReasoningJob candidate) {
        if (candidate == null) {
            return false;
        }
        // Global heap-safety veto — genuinely shared across lanes, so a free lane slot never
        // overrides real memory pressure. Deliberately NOT gated on the old configuredMax/
        // slotBudget combo: that sizing made a heavy job consume the *entire* shared budget
        // by design, which — combined with a single FIFO queue — was exactly what let one
        // heavy job block every other job regardless of lane. Lane caps below are now the
        // primary admission gate.
        if (memoryAware && !activeJobs.isEmpty() && freeHeapRatio() < minFreeHeapRatio) {
            return false;
        }
        boolean heavy = isHeavy(candidate);
        long activeInLane = activeJobs.stream().filter(j -> isHeavy(j) == heavy).count();
        int laneMax = heavy ? heavyLaneMaxConcurrent : fastLaneMaxConcurrent;
        return activeInLane < laneMax;
    }

    private int maxJobsFromHeap(Collection<ReasoningJob> activeJobs, double freeRatio) {
        if (!memoryAware) {
            return configuredMax;
        }
        if (activeJobs.isEmpty()) {
            return configuredMax;
        }
        if (freeRatio < minFreeHeapRatio) {
            return activeJobs.size();
        }
        if (freeRatio < 0.35) {
            return 1;
        }
        if (freeRatio < 0.50) {
            return Math.min(2, configuredMax);
        }
        return configuredMax;
    }

    private double freeHeapRatio() {
        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = bean.getHeapMemoryUsage();
        long max = heap.getMax();
        if (max <= 0) {
            return 1.0;
        }
        return Math.max(0.0, (double) (max - heap.getUsed()) / max);
    }
}
