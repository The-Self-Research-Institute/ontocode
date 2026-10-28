package self.research.ontology.owlEditor.service.infer;

import java.util.function.BooleanSupplier;

public final class HeapBudget {

    private static final double MIN_FREE_FRACTION = 0.25;

    private final BooleanSupplier low;

    public HeapBudget(BooleanSupplier low) {
        this.low = low;
    }

    public static HeapBudget runtime() {
        return new HeapBudget(() -> {
            Runtime rt = Runtime.getRuntime();
            long max = rt.maxMemory();
            long used = rt.totalMemory() - rt.freeMemory();
            return max - used < (long) (max * MIN_FREE_FRACTION);
        });
    }

    public boolean isLow() {
        return low.getAsBoolean();
    }
}
