package self.research.ontology.common;

import self.research.ontology.common.admission.CgroupAwareMemorySignals;
import self.research.ontology.common.admission.MemorySignals;

public final class ReasoningHeapMonitor {

    private static final MemorySignals SIGNALS = new CgroupAwareMemorySignals();

    private ReasoningHeapMonitor() {}

    public static MemorySignals signals() {
        return SIGNALS;
    }

    public static double usedRatio() {
        return SIGNALS.heapUsedRatio().orElse(0);
    }

    public static double heapAfterGcRatio() {
        return SIGNALS.heapAfterGcRatio().orElse(0);
    }

    public static double freeRatio() {
        return Math.max(0, 1.0 - usedRatio());
    }

    public static long usedMb() {
        return SIGNALS.heapUsedBytes() / (1024 * 1024);
    }
}
