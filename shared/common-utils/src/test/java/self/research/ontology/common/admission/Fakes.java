package self.research.ontology.common.admission;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

final class Fakes {

    private Fakes() {}

    static final class FakeSignals implements MemorySignals {
        volatile long heapMax = 1000;
        volatile long heapUsed = 0;
        volatile long afterGc = 0;
        volatile boolean afterGcAvailable = true;
        volatile Metric limit = Metric.unavailable();
        volatile Metric usage = Metric.unavailable();

        @Override
        public Metric containerLimit() {
            return limit;
        }

        @Override
        public Metric containerUsageRaw() {
            return usage;
        }

        @Override
        public Metric containerUsageAdjusted() {
            return usage;
        }

        @Override
        public long heapMaxBytes() {
            return heapMax;
        }

        @Override
        public long heapUsedBytes() {
            return heapUsed;
        }

        @Override
        public HeapAfterGc heapAfterGc() {
            return new HeapAfterGc(afterGcAvailable ? afterGc : heapUsed, afterGcAvailable);
        }

        @Override
        public Metric directBufferBytes() {
            return Metric.available(0);
        }

        @Override
        public Metric mappedBufferBytes() {
            return Metric.unavailable();
        }
    }

    static final class FakeClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-28T00:00:00Z"));

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    static AdmissionProperties props(int maxActivePerOwner, int maxWaitingPerOwner, int maxWaiters) {
        return new AdmissionProperties(0.0, 0.75, 0.85, Duration.ofSeconds(5), maxWaiters, maxActivePerOwner,
                maxWaitingPerOwner, Duration.ofMinutes(30));
    }
}
