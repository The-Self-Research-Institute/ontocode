package self.research.ontology.common;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

public final class ReasoningHeapMonitor {

    private ReasoningHeapMonitor() {}

    public static double usedRatio() {
        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = bean.getHeapMemoryUsage();
        long max = heap.getMax();
        if (max <= 0) {
            return 0;
        }
        return (double) heap.getUsed() / max;
    }

    public static double freeRatio() {
        return Math.max(0, 1.0 - usedRatio());
    }

    public static long usedMb() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return heap.getUsed() / (1024 * 1024);
    }
}
