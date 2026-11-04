package self.research.ontology.common.admission;

import java.util.OptionalDouble;

public interface MemorySignals {

    enum Status { AVAILABLE, UNLIMITED, UNAVAILABLE }

    record Metric(Status status, long bytes) {
        private static final Metric UNLIMITED_METRIC = new Metric(Status.UNLIMITED, -1);
        private static final Metric UNAVAILABLE_METRIC = new Metric(Status.UNAVAILABLE, -1);

        public static Metric available(long bytes) {
            return bytes < 0 ? UNAVAILABLE_METRIC : new Metric(Status.AVAILABLE, bytes);
        }

        public static Metric unlimited() {
            return UNLIMITED_METRIC;
        }

        public static Metric unavailable() {
            return UNAVAILABLE_METRIC;
        }

        public boolean isAvailable() {
            return status == Status.AVAILABLE;
        }

        public boolean isUnlimited() {
            return status == Status.UNLIMITED;
        }

        public boolean isUnavailable() {
            return status == Status.UNAVAILABLE;
        }

        @Override
        public String toString() {
            return status == Status.AVAILABLE ? Long.toString(bytes) : status.name();
        }
    }

    record HeapAfterGc(long bytes, boolean afterGcAvailable) {}

    record Report(
            String containerSource,
            Metric containerLimit,
            Metric containerUsageRaw,
            Metric containerUsageAdjusted,
            long heapMaxBytes,
            long heapUsedBytes,
            long heapAfterGcBytes,
            boolean afterGcAvailable,
            Metric directBufferBytes,
            Metric mappedBufferBytes) {

        @Override
        public String toString() {
            return "memory[container=" + containerSource
                    + " limit=" + containerLimit
                    + " usageRaw=" + containerUsageRaw
                    + " usageAdjusted=" + containerUsageAdjusted
                    + " heapMax=" + heapMaxBytes
                    + " heapUsed=" + heapUsedBytes
                    + " heapAfterGc=" + heapAfterGcBytes + (afterGcAvailable ? "" : " (not measured after a GC, using heap used)")
                    + " directBuffers=" + directBufferBytes
                    + " mappedBuffers=" + mappedBufferBytes + "]";
        }
    }

    Metric containerLimit();

    Metric containerUsageRaw();

    Metric containerUsageAdjusted();

    long heapMaxBytes();

    long heapUsedBytes();

    HeapAfterGc heapAfterGc();

    Metric directBufferBytes();

    Metric mappedBufferBytes();

    default String containerSource() {
        return "unknown";
    }

    default OptionalDouble heapUsedRatio() {
        long max = heapMaxBytes();
        if (max <= 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of((double) heapUsedBytes() / max);
    }

    default OptionalDouble heapAfterGcRatio() {
        long max = heapMaxBytes();
        if (max <= 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of((double) heapAfterGc().bytes() / max);
    }

    default OptionalDouble containerUsageRatio() {
        Metric limit = containerLimit();
        Metric usage = containerUsageAdjusted();
        if (!limit.isAvailable() || limit.bytes() <= 0 || !usage.isAvailable()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of((double) usage.bytes() / limit.bytes());
    }

    default Report report() {
        HeapAfterGc afterGc = heapAfterGc();
        return new Report(containerSource(), containerLimit(), containerUsageRaw(), containerUsageAdjusted(),
                heapMaxBytes(), heapUsedBytes(), afterGc.bytes(), afterGc.afterGcAvailable(),
                directBufferBytes(), mappedBufferBytes());
    }
}
