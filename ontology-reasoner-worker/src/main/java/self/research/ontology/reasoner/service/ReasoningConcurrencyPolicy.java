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

    public ReasoningConcurrencyPolicy(EditorClient editorClient) {
        this.editorClient = editorClient;
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
        if (tripleCount <= 0) {
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
        ConcurrencySnapshot snap = snapshot(activeJobs);
        if (activeJobs.size() >= snap.effectiveMax()) {
            return false;
        }
        return usedSlots(activeJobs) + candidate.getSlotWeight() <= snap.slotBudget();
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
