package self.research.ontology.common.admission;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

public final class ReservationLedger {

    public static final long RETRY_AFTER_BUSY_MILLIS = 1_000;
    public static final long RETRY_AFTER_PRESSURE_MILLIS = 5_000;

    public record Leak(long id, String owner, String kind, long bytes, Duration age) {}

    private static final class Waiter {
        final String owner;
        final String kind;
        final long bytes;
        final Condition condition;
        final long startNanos = System.nanoTime();
        boolean shareBlocked;
        AdmissionResult outcome;

        Waiter(String owner, String kind, long bytes, Condition condition) {
            this.owner = owner;
            this.kind = kind;
            this.bytes = bytes;
            this.condition = condition;
        }
    }

    private final MemorySignals signals;
    private final CapacityConfig config;
    private final AdmissionProperties props;
    private final LongSupplier baseline;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Long, Reservation> active = new LinkedHashMap<>();
    private final Map<String, Long> reservedByKind = new HashMap<>();
    private final Map<String, Integer> activeByKind = new HashMap<>();
    private final Map<String, Integer> activeByOwnerKind = new HashMap<>();
    private final Map<String, Integer> waitingByOwnerKind = new HashMap<>();
    private final Map<String, Integer> waitingByKind = new HashMap<>();
    private final ArrayDeque<Waiter> queue = new ArrayDeque<>();
    private final List<CacheAccount> caches = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private final LedgerStats stats = new LedgerStats(this);
    private long reserved;
    private long peakReserved;

    public ReservationLedger(MemorySignals signals, AdmissionProperties props, LongSupplier baseline, Clock clock) {
        this(signals, CapacityConfig.of(props), baseline, clock);
    }

    public ReservationLedger(MemorySignals signals, CapacityConfig config, LongSupplier baseline, Clock clock) {
        this.signals = signals;
        this.config = config;
        this.props = config.admission();
        this.baseline = baseline == null ? () -> 0L : baseline;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public CacheAccount cacheAccount(String name) {
        CacheAccount account = new CacheAccount(name);
        caches.add(account);
        return account;
    }

    public CapacityConfig config() {
        return config;
    }

    public MemorySignals signals() {
        return signals;
    }

    public LedgerStats stats() {
        return stats;
    }

    public long baselineBytes() {
        long total = Math.max(0, baseline.getAsLong());
        for (CacheAccount account : caches) {
            total += account.bytes();
            if (total < 0) {
                return Long.MAX_VALUE;
            }
        }
        return total;
    }

    public long capacityBytes() {
        long ceiling = signals.heapMaxBytes() > 0 ? signals.heapMaxBytes() : Long.MAX_VALUE;
        MemorySignals.Metric limit = signals.containerLimit();
        if (limit.isAvailable() && limit.bytes() > 0) {
            ceiling = Math.min(ceiling, limit.bytes());
        }
        if (ceiling == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        long usable = ceiling - baselineBytes();
        if (usable <= 0) {
            return 0;
        }
        return (long) (usable * (1.0 - props.headroomFraction()));
    }

    public long reservedBytes() {
        lock.lock();
        try {
            return reserved;
        } finally {
            lock.unlock();
        }
    }

    public long availableBytes() {
        lock.lock();
        try {
            return Math.max(0, capacityBytes() - reserved);
        } finally {
            lock.unlock();
        }
    }

    public AdmissionResult tryReserve(String owner, WorkKind kind, long estimateBytes) {
        return tryReserve(owner, kind.name(), estimateBytes);
    }

    public AdmissionResult acquire(String owner, WorkKind kind, long estimateBytes) {
        return acquire(owner, kind.name(), estimateBytes, config.kind(kind).maxWait());
    }

    public AdmissionResult acquire(String owner, WorkKind kind, long estimateBytes, Duration maxWait) {
        return acquire(owner, kind.name(), estimateBytes, maxWait);
    }

    public AdmissionResult tryReserve(String owner, String kind, long estimateBytes) {
        return admit(owner, kind, estimateBytes, Duration.ZERO);
    }

    public AdmissionResult acquire(String owner, String kind, long estimateBytes, Duration maxWait) {
        Duration wait = maxWait == null ? Duration.ZERO : maxWait;
        if (!wait.isZero() && !wait.isNegative() && BudgetContext.holdsReservation()) {
            throw new IllegalStateException("nested blocking acquire");
        }
        return admit(owner, kind, estimateBytes, wait);
    }

    private AdmissionResult admit(String owner, String kind, long bytes, Duration maxWait) {
        String safeKind = kind == null || kind.isBlank() ? "unknown" : kind;
        stats.offered(safeKind);
        if (owner == null || owner.isBlank() || kind == null || kind.isBlank() || bytes <= 0) {
            return refuse(safeKind, RefusalReason.INVALID,
                    "The request is missing an owner, a kind or a positive memory estimate.", 0);
        }
        Reservation parent = BudgetContext.activeHolder();
        String effectiveOwner = parent != null ? parent.owner() : owner;
        KindSettings settings = config.settingsFor(kind);
        boolean willWait = !maxWait.isZero() && !maxWait.isNegative();
        lock.lock();
        boolean interrupted = false;
        try {
            AdmissionResult blocked = evaluate(effectiveOwner, kind, bytes, parent != null, settings);
            boolean waitersAhead = blocked == null && hasWaitersAheadOf(kind);
            if (blocked == null && !waitersAhead) {
                return AdmissionResult.admitted(register(effectiveOwner, kind, bytes, parent));
            }
            boolean waitable = waitersAhead
                    || blocked.reason() == RefusalReason.OVER_CAPACITY
                    || blocked.reason() == RefusalReason.KIND_SHARE_EXCEEDED;
            if (!waitable) {
                return refuse(kind, blocked.reason(), blocked.message(), blocked.retryAfterMillis());
            }
            if (!willWait) {
                if (blocked != null) {
                    return refuse(kind, blocked.reason(), blocked.message(), blocked.retryAfterMillis());
                }
                return refuse(kind, RefusalReason.OVER_CAPACITY,
                        "Other requests are already waiting for memory; try again shortly.",
                        RETRY_AFTER_BUSY_MILLIS);
            }
            String ownerKey = key(effectiveOwner, kind);
            if (waitingByOwnerKind.getOrDefault(ownerKey, 0) >= settings.maxWaitingPerOwner()) {
                return refuse(kind, RefusalReason.OWNER_LIMIT,
                        "You already have a request of this kind waiting for memory.", RETRY_AFTER_BUSY_MILLIS);
            }
            if (queue.size() >= props.maxWaiters() || waitingByKind.getOrDefault(kind, 0) >= settings.maxWaiters()) {
                return refuse(kind, RefusalReason.QUEUE_FULL,
                        "Too many requests are waiting for memory right now.", RETRY_AFTER_BUSY_MILLIS);
            }
            Waiter waiter = new Waiter(effectiveOwner, kind, bytes, lock.newCondition());
            waiter.shareBlocked = blocked != null && blocked.reason() == RefusalReason.KIND_SHARE_EXCEEDED;
            queue.addLast(waiter);
            waitingByOwnerKind.merge(ownerKey, 1, Integer::sum);
            waitingByKind.merge(kind, 1, Integer::sum);
            long remaining = maxWait.toNanos();
            while (waiter.outcome == null) {
                if (remaining <= 0) {
                    dequeue(waiter);
                    waiter.outcome = refuse(kind, RefusalReason.WAIT_TIMEOUT,
                            "Not enough memory became free within the waiting time.", RETRY_AFTER_BUSY_MILLIS);
                    stats.waited(kind, System.nanoTime() - waiter.startNanos);
                    processQueue();
                    break;
                }
                try {
                    remaining = waiter.condition.awaitNanos(remaining);
                } catch (InterruptedException e) {
                    interrupted = true;
                    if (waiter.outcome == null) {
                        dequeue(waiter);
                        waiter.outcome = refuse(kind, RefusalReason.INTERRUPTED,
                                "The wait for memory was interrupted.", 0);
                        stats.waited(kind, System.nanoTime() - waiter.startNanos);
                        processQueue();
                    }
                }
            }
            return waiter.outcome;
        } finally {
            lock.unlock();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean hasWaitersAheadOf(String kind) {
        for (Waiter w : queue) {
            if (!w.shareBlocked || w.kind.equals(kind)) {
                return true;
            }
        }
        return false;
    }

    private AdmissionResult evaluate(String owner, String kind, long bytes, boolean nested, KindSettings settings) {
        long capacity = capacityBytes();
        long shareCap = settings.maxShare() >= 1.0 ? capacity : (long) (capacity * settings.maxShare());
        if (bytes > capacity) {
            return AdmissionResult.refused(RefusalReason.TOO_LARGE,
                    "This request is estimated to need more memory than the server can ever give one request.", 0);
        }
        if (bytes > shareCap) {
            return AdmissionResult.refused(RefusalReason.TOO_LARGE,
                    "This request is estimated to need more memory than this kind of work is allowed to use.", 0);
        }
        OptionalPressure pressure = pressure();
        if (pressure.reason != null) {
            return AdmissionResult.refused(pressure.reason, pressure.message, RETRY_AFTER_PRESSURE_MILLIS);
        }
        if (!nested && activeByOwnerKind.getOrDefault(key(owner, kind), 0) >= settings.maxActivePerOwner()) {
            return AdmissionResult.refused(RefusalReason.OWNER_LIMIT,
                    "You already have the maximum number of requests of this kind running.", RETRY_AFTER_BUSY_MILLIS);
        }
        if (bytes > shareCap - reservedByKind.getOrDefault(kind, 0L)) {
            return AdmissionResult.refused(RefusalReason.KIND_SHARE_EXCEEDED,
                    "This kind of work has used its share of server memory; try again when one finishes.",
                    RETRY_AFTER_BUSY_MILLIS);
        }
        if (bytes > capacity - reserved) {
            return AdmissionResult.refused(RefusalReason.OVER_CAPACITY,
                    "The server does not have enough free memory for this request right now.",
                    RETRY_AFTER_BUSY_MILLIS);
        }
        return null;
    }

    private record OptionalPressure(RefusalReason reason, String message) {}

    private OptionalPressure pressure() {
        var heap = signals.heapAfterGcRatio();
        if (heap.isPresent() && heap.getAsDouble() > props.heapAfterGcRefuseRatio()) {
            return new OptionalPressure(RefusalReason.HEAP_PRESSURE,
                    "The server's memory is under pressure after its last cleanup; try again in a few seconds.");
        }
        var container = signals.containerUsageRatio();
        if (container.isPresent() && container.getAsDouble() > props.containerRefuseRatio()) {
            return new OptionalPressure(RefusalReason.CONTAINER_PRESSURE,
                    "The server's container is close to its memory limit; try again in a few seconds.");
        }
        return new OptionalPressure(null, null);
    }

    private Reservation register(String owner, String kind, long bytes, Reservation parent) {
        Reservation reservation = new Reservation(ids.incrementAndGet(), owner, kind, bytes, clock.instant(),
                parent == null ? 0 : parent.id(), this);
        active.put(reservation.id(), reservation);
        reserved += bytes;
        peakReserved = Math.max(peakReserved, reserved);
        reservedByKind.merge(kind, bytes, Long::sum);
        activeByKind.merge(kind, 1, Integer::sum);
        if (parent == null) {
            activeByOwnerKind.merge(key(owner, kind), 1, Integer::sum);
        }
        stats.admitted(kind, parent != null);
        return reservation;
    }

    private AdmissionResult refuse(String kind, RefusalReason reason, String message, long retryAfterMillis) {
        stats.refused(kind, reason);
        return AdmissionResult.refused(reason, message, retryAfterMillis);
    }

    private void dequeue(Waiter waiter) {
        if (queue.remove(waiter)) {
            decrement(waitingByOwnerKind, key(waiter.owner, waiter.kind));
            decrement(waitingByKind, waiter.kind);
        }
    }

    private static void decrement(Map<String, Integer> map, String key) {
        map.computeIfPresent(key, (k, v) -> v <= 1 ? null : v - 1);
    }

    private void processQueue() {
        java.util.Set<String> shareBlockedKinds = new java.util.HashSet<>();
        Iterator<Waiter> it = queue.iterator();
        while (it.hasNext()) {
            Waiter w = it.next();
            if (shareBlockedKinds.contains(w.kind)) {
                continue;
            }
            AdmissionResult result = evaluate(w.owner, w.kind, w.bytes, false, config.settingsFor(w.kind));
            if (result == null) {
                it.remove();
                decrement(waitingByOwnerKind, key(w.owner, w.kind));
                decrement(waitingByKind, w.kind);
                w.outcome = AdmissionResult.admitted(register(w.owner, w.kind, w.bytes, null));
                stats.waited(w.kind, System.nanoTime() - w.startNanos);
                w.condition.signal();
            } else if (result.reason() == RefusalReason.KIND_SHARE_EXCEEDED) {
                w.shareBlocked = true;
                shareBlockedKinds.add(w.kind);
            } else if (result.reason() == RefusalReason.OVER_CAPACITY) {
                w.shareBlocked = false;
                return;
            } else {
                it.remove();
                decrement(waitingByOwnerKind, key(w.owner, w.kind));
                decrement(waitingByKind, w.kind);
                w.outcome = refuse(w.kind, result.reason(), result.message(), result.retryAfterMillis());
                stats.waited(w.kind, System.nanoTime() - w.startNanos);
                w.condition.signal();
            }
        }
    }

    void onRelease(Reservation reservation) {
        lock.lock();
        try {
            if (active.remove(reservation.id()) == null) {
                return;
            }
            reserved -= reservation.bytes();
            reservedByKind.computeIfPresent(reservation.kind(), (k, v) -> v - reservation.bytes() <= 0 ? null : v - reservation.bytes());
            decrement(activeByKind, reservation.kind());
            if (reservation.parentId() == 0) {
                decrement(activeByOwnerKind, key(reservation.owner(), reservation.kind()));
            }
            stats.completed(reservation.kind());
            processQueue();
        } finally {
            lock.unlock();
        }
    }

    void onDoubleRelease(Reservation reservation) {
        stats.doubleRelease();
    }

    LedgerStats.Snapshot snapshotStats(LedgerStats source) {
        lock.lock();
        try {
            return source.build(active.size(), reserved, peakReserved, queue.size());
        } finally {
            lock.unlock();
        }
    }

    public List<Leak> leaks(Duration olderThan) {
        Instant now = clock.instant();
        List<Leak> result = new ArrayList<>();
        lock.lock();
        try {
            for (Reservation r : active.values()) {
                Duration age = Duration.between(r.createdAt(), now);
                if (age.compareTo(olderThan) >= 0) {
                    result.add(new Leak(r.id(), r.owner(), r.kind(), r.bytes(), age));
                }
            }
        } finally {
            lock.unlock();
        }
        return result;
    }

    public CapacityReport capacityReport() {
        return capacityReport(props.watchdogMaxAge());
    }

    public CapacityReport capacityReport(Duration leakAge) {
        lock.lock();
        try {
            long capacity = capacityBytes();
            List<CapacityReport.KindReport> kinds = new ArrayList<>();
            java.util.Set<String> names = new java.util.TreeSet<>();
            for (WorkKind k : WorkKind.values()) {
                names.add(k.name());
            }
            names.addAll(activeByKind.keySet());
            for (String name : names) {
                KindSettings s = config.settingsFor(name);
                long shareCap = s.maxShare() >= 1.0 ? capacity : (long) (capacity * s.maxShare());
                long reservedKind = reservedByKind.getOrDefault(name, 0L);
                WorkKind wk = WorkKind.find(name);
                kinds.add(new CapacityReport.KindReport(name, wk == null ? List.of() : wk.services(),
                        activeByKind.getOrDefault(name, 0), waitingByKind.getOrDefault(name, 0), reservedKind,
                        shareCap, s.maxShare(),
                        capacity == Long.MAX_VALUE || capacity == 0 ? 0.0 : (double) reservedKind / capacity,
                        stats.kindSnapshot(name)));
            }
            Map<String, Long> cacheBytes = new TreeMap<>();
            for (CacheAccount account : caches) {
                cacheBytes.merge(account.name(), account.bytes(), Long::sum);
            }
            return new CapacityReport(clock.instant(), capacity, baselineBytes(), reserved, peakReserved,
                    props.headroomFraction(), kinds, cacheBytes, signals.report(),
                    stats.build(active.size(), reserved, peakReserved, queue.size()),
                    config.effectiveValues(), sourceText(), leaks(leakAge), leakAge);
        } finally {
            lock.unlock();
        }
    }

    private Map<String, String> sourceText() {
        Map<String, String> out = new TreeMap<>();
        config.sources().forEach((k, v) -> out.put(k, v.toString()));
        return out;
    }

    private static String key(String owner, String kind) {
        return owner + '\u0000' + kind;
    }
}
