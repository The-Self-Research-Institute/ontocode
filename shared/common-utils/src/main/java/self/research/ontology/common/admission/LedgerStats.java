package self.research.ontology.common.admission;

import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class LedgerStats {

    public record KindSnapshot(
            long offered,
            long admitted,
            long completed,
            Map<RefusalReason, Long> refusedByReason,
            long refusedTotal,
            long waitCount,
            long waitSumNanos,
            long waitMaxNanos) {}

    public record Snapshot(
            long offered,
            long admitted,
            long nestedAdmitted,
            long completed,
            Map<RefusalReason, Long> refusedByReason,
            long refusedTotal,
            long activeCount,
            long reservedBytes,
            long peakReservedBytes,
            long waitCount,
            long waitSumNanos,
            long waitMaxNanos,
            long doubleReleaseAttempts,
            long waitingNow,
            Map<String, KindSnapshot> byKind) {}

    private static final class Counters {
        final AtomicLong offered = new AtomicLong();
        final AtomicLong admitted = new AtomicLong();
        final AtomicLong completed = new AtomicLong();
        final AtomicLong waitCount = new AtomicLong();
        final AtomicLong waitSumNanos = new AtomicLong();
        final AtomicLong waitMaxNanos = new AtomicLong();
        final AtomicLong[] refused = new AtomicLong[RefusalReason.values().length];

        Counters() {
            for (int i = 0; i < refused.length; i++) {
                refused[i] = new AtomicLong();
            }
        }

        KindSnapshot snapshot() {
            Map<RefusalReason, Long> byReason = new EnumMap<>(RefusalReason.class);
            long total = 0;
            for (RefusalReason reason : RefusalReason.values()) {
                long count = refused[reason.ordinal()].get();
                byReason.put(reason, count);
                total += count;
            }
            return new KindSnapshot(offered.get(), admitted.get(), completed.get(), Map.copyOf(byReason), total,
                    waitCount.get(), waitSumNanos.get(), waitMaxNanos.get());
        }
    }

    private final Counters total = new Counters();
    private final Map<String, Counters> kinds = new ConcurrentHashMap<>();
    private final AtomicLong nestedAdmitted = new AtomicLong();
    private final AtomicLong doubleReleases = new AtomicLong();
    private final ReservationLedger ledger;

    LedgerStats(ReservationLedger ledger) {
        this.ledger = ledger;
    }

    private Counters of(String kind) {
        return kinds.computeIfAbsent(kind, k -> new Counters());
    }

    void offered(String kind) {
        total.offered.incrementAndGet();
        of(kind).offered.incrementAndGet();
    }

    void admitted(String kind, boolean nested) {
        total.admitted.incrementAndGet();
        of(kind).admitted.incrementAndGet();
        if (nested) {
            nestedAdmitted.incrementAndGet();
        }
    }

    void completed(String kind) {
        total.completed.incrementAndGet();
        of(kind).completed.incrementAndGet();
    }

    void refused(String kind, RefusalReason reason) {
        total.refused[reason.ordinal()].incrementAndGet();
        of(kind).refused[reason.ordinal()].incrementAndGet();
    }

    void waited(String kind, long nanos) {
        for (Counters c : new Counters[] {total, of(kind)}) {
            c.waitCount.incrementAndGet();
            c.waitSumNanos.addAndGet(nanos);
            c.waitMaxNanos.accumulateAndGet(nanos, Math::max);
        }
    }

    void doubleRelease() {
        doubleReleases.incrementAndGet();
    }

    public Snapshot snapshot() {
        return ledger.snapshotStats(this);
    }

    KindSnapshot kindSnapshot(String kind) {
        return of(kind).snapshot();
    }

    Snapshot build(long activeCount, long reservedBytes, long peakReservedBytes, long waitingNow) {
        KindSnapshot all = total.snapshot();
        Map<String, KindSnapshot> byKind = new TreeMap<>();
        for (Map.Entry<String, Counters> e : kinds.entrySet()) {
            byKind.put(e.getKey(), e.getValue().snapshot());
        }
        return new Snapshot(all.offered(), all.admitted(), nestedAdmitted.get(), all.completed(),
                all.refusedByReason(), all.refusedTotal(), activeCount, reservedBytes, peakReservedBytes,
                all.waitCount(), all.waitSumNanos(), all.waitMaxNanos(), doubleReleases.get(), waitingNow,
                Map.copyOf(byKind));
    }
}
