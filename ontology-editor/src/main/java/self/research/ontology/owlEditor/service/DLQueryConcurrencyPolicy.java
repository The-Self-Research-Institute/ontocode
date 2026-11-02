package self.research.ontology.owlEditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.common.ReasoningHeapMonitor;
import self.research.ontology.common.admission.MemorySignals;
import self.research.ontology.owlEditor.model.DLQueryJob;

import java.util.Collection;

@Slf4j
@Service
public class DLQueryConcurrencyPolicy {

    private final ImportQueueManager importQueueManager;

    @Value("${ontocode.dlquery.max-concurrent:1}")
    private int configuredMax;

    @Value("${ontocode.dlquery.memory-aware:true}")
    private boolean memoryAware;

    @Value("${ontocode.dlquery.min-free-heap-ratio:0.20}")
    private double minFreeHeapRatio;

    @Value("${ontocode.dlquery.large-triple-threshold:500000}")
    private long largeTripleThreshold;

    @Value("${ontocode.dlquery.slot-budget:${ontocode.dlquery.max-concurrent:1}}")
    private int slotBudget;

    @Value("${ontocode.dlquery.yield-to-imports:true}")
    private boolean yieldToImports;

    private final MemorySignals signals;

    @org.springframework.beans.factory.annotation.Autowired
    public DLQueryConcurrencyPolicy(ImportQueueManager importQueueManager, MemorySignals signals) {
        this.importQueueManager = importQueueManager;
        this.signals = signals;
    }

    public DLQueryConcurrencyPolicy(ImportQueueManager importQueueManager) {
        this(importQueueManager, ReasoningHeapMonitor.signals());
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

    public int usedSlots(Collection<DLQueryJob> activeJobs) {
        return activeJobs.stream().mapToInt(DLQueryJob::getSlotWeight).sum();
    }

    public ConcurrencySnapshot snapshot(Collection<DLQueryJob> activeJobs) {
        int configured = Math.max(1, configuredMax);
        int budget = Math.max(1, slotBudget);
        double freeRatio = freeHeapRatio();
        boolean importActive = yieldToImports
                && importQueueManager.getQueueStats().getActiveImports() > 0;
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

        int used = usedSlots(activeJobs);
        return new ConcurrencySnapshot(
                configured,
                Math.max(0, effective),
                budget,
                used,
                freeRatio,
                memoryPressure,
                importActive,
                reason
        );
    }

    public boolean canStartAnother(Collection<DLQueryJob> activeJobs, DLQueryJob candidate) {
        if (candidate == null) {
            return false;
        }
        ConcurrencySnapshot snap = snapshot(activeJobs);
        if (activeJobs.size() >= snap.effectiveMax()) {
            return false;
        }
        return usedSlots(activeJobs) + candidate.getSlotWeight() <= snap.slotBudget();
    }

    private int maxJobsFromHeap(Collection<DLQueryJob> activeJobs, double freeRatio) {
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
        return Math.max(0.0, 1.0 - signals.heapUsedRatio().orElse(0));
    }
}
