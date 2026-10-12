package self.research.ontology.owlEditor.service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class StampedComputeCache<V> {

    private static final long FAILURE_TTL_MS = 30_000;

    private record Stamped<V>(String stamp, V value, long expiresAt) {}

    private final Map<String, Stamped<V>> cached = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Stamped<V>>> inFlight = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    StampedComputeCache() {
        this(System::currentTimeMillis);
    }

    StampedComputeCache(LongSupplier clock) {
        this.clock = clock;
    }

    V get(String key, String stamp, Supplier<V> compute, V fallback) {
        Stamped<V> hit = cached.get(key);
        if (stamp != null && hit != null && stamp.equals(hit.stamp()) && clock.getAsLong() < hit.expiresAt()) {
            return hit.value();
        }
        CompletableFuture<Stamped<V>> mine = new CompletableFuture<>();
        CompletableFuture<Stamped<V>> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            Stamped<V> shared = running.join();
            if (shared != null && stamp != null && stamp.equals(shared.stamp())) {
                return shared.value();
            }
            V fresh = compute.get();
            return fresh != null ? fresh : fallback;
        }
        Stamped<V> result = null;
        try {
            V fresh = compute.get();
            long expiresAt = fresh != null ? Long.MAX_VALUE : clock.getAsLong() + FAILURE_TTL_MS;
            result = new Stamped<>(stamp, fresh != null ? fresh : fallback, expiresAt);
            if (stamp != null) {
                cached.put(key, result);
            }
            return result.value();
        } finally {
            mine.complete(result);
            inFlight.remove(key, mine);
        }
    }
}
