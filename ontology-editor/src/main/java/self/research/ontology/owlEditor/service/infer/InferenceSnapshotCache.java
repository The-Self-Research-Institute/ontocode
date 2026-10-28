package self.research.ontology.owlEditor.service.infer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

public final class InferenceSnapshotCache {

    private static final int MEMORY_OF_SCOPES = 1000;

    private final int maxSnapshots;
    private final long ttlMillis;
    private final long maxTriples;
    private final LongSupplier clock;
    private InferenceCacheAccounting accounting = InferenceCacheAccounting.NONE;
    private static final long BYTES_PER_TRIPLE = 300L;
    private final LinkedHashMap<InferenceSnapshotKey, InferredSnapshot> entries = new LinkedHashMap<>(8, 0.75f, true);
    private final Map<InferenceSnapshotKey, Long> storedAt = new HashMap<>();
    private final Map<String, Instant> lastComputed = new HashMap<>();
    private final Map<InferenceSnapshotKey, String> failures = new HashMap<>();

    public InferenceSnapshotCache(int maxSnapshots, long ttlMillis, long maxTriples, LongSupplier clock) {
        this.maxSnapshots = maxSnapshots;
        this.ttlMillis = ttlMillis;
        this.maxTriples = maxTriples;
        this.clock = clock;
    }

    public synchronized void setAccounting(InferenceCacheAccounting accounting) {
        this.accounting = accounting;
    }

    public static InferenceSnapshotCache standard() {
        return new InferenceSnapshotCache(InferenceProfile.CACHE_MAX_SNAPSHOTS, InferenceProfile.CACHE_TTL_MS,
                InferenceProfile.CACHE_MAX_TRIPLES, System::currentTimeMillis);
    }

    public synchronized Optional<InferredSnapshot> acquire(InferenceSnapshotKey lookup) {
        expire();
        InferredSnapshot snapshot = entries.get(lookup.lookupKey());
        if (snapshot == null) {
            dropOtherRevisions(lookup);
            return Optional.empty();
        }
        snapshot.acquire();
        return Optional.of(snapshot);
    }

    private void dropOtherRevisions(InferenceSnapshotKey lookup) {
        String scope = scopeKey(lookup);
        Iterator<Map.Entry<InferenceSnapshotKey, InferredSnapshot>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<InferenceSnapshotKey, InferredSnapshot> e = it.next();
            if (scopeKey(e.getKey()).equals(scope) && !e.getKey().equals(lookup.lookupKey())) {
                it.remove();
                storedAt.remove(e.getKey());
                e.getValue().retire(accounting, BYTES_PER_TRIPLE);
            }
        }
    }

    public synchronized InferredSnapshot put(InferredSnapshot snapshot) {
        InferenceSnapshotKey lookup = snapshot.key().lookupKey();
        dropOtherRevisions(lookup);
        InferredSnapshot previous = entries.put(lookup, snapshot);
        if (previous != null) {
            previous.retire(accounting, BYTES_PER_TRIPLE);
        }
        storedAt.put(lookup, clock.getAsLong());
        failures.remove(lookup);
        if (lastComputed.size() > MEMORY_OF_SCOPES) {
            lastComputed.clear();
        }
        lastComputed.put(scopeKey(snapshot.key()), snapshot.computedAt());
        accounting.add(snapshot.size() * BYTES_PER_TRIPLE);
        snapshot.acquire();
        evict(snapshot);
        return snapshot;
    }

    public synchronized Optional<Instant> lastComputed(InferenceSnapshotKey key) {
        return Optional.ofNullable(lastComputed.get(scopeKey(key)));
    }

    public synchronized void recordFailure(InferenceSnapshotKey lookup, String reason) {
        if (failures.size() > MEMORY_OF_SCOPES) {
            failures.clear();
        }
        failures.put(lookup.lookupKey(), reason);
    }

    public synchronized Optional<String> failure(InferenceSnapshotKey lookup) {
        return Optional.ofNullable(failures.get(lookup.lookupKey()));
    }

    public synchronized int size() {
        expire();
        return entries.size();
    }

    public synchronized void clear() {
        for (InferredSnapshot s : new ArrayList<>(entries.values())) {
            s.retire(accounting, BYTES_PER_TRIPLE);
        }
        entries.clear();
        storedAt.clear();
    }

    private void expire() {
        long now = clock.getAsLong();
        Iterator<Map.Entry<InferenceSnapshotKey, InferredSnapshot>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<InferenceSnapshotKey, InferredSnapshot> e = it.next();
            Long stored = storedAt.get(e.getKey());
            if (stored != null && now - stored > ttlMillis) {
                it.remove();
                storedAt.remove(e.getKey());
                e.getValue().retire(accounting, BYTES_PER_TRIPLE);
            }
        }
    }

    private void evict(InferredSnapshot keep) {
        while (entries.size() > maxSnapshots || (entries.size() > 1 && total() > maxTriples)) {
            Iterator<Map.Entry<InferenceSnapshotKey, InferredSnapshot>> it = entries.entrySet().iterator();
            Map.Entry<InferenceSnapshotKey, InferredSnapshot> eldest = it.next();
            if (eldest.getValue() == keep && it.hasNext()) {
                eldest = it.next();
            }
            if (eldest.getValue() == keep) {
                return;
            }
            entries.remove(eldest.getKey());
            storedAt.remove(eldest.getKey());
            eldest.getValue().retire(accounting, BYTES_PER_TRIPLE);
        }
    }

    private long total() {
        long sum = 0;
        for (InferredSnapshot s : entries.values()) {
            sum += s.size();
        }
        return sum;
    }

    private static String scopeKey(InferenceSnapshotKey key) {
        return key.projectId() + "\u0001" + key.scope() + "\u0001" + key.ownerId();
    }

    public List<InferenceSnapshotKey> keys() {
        synchronized (this) {
            return new ArrayList<>(entries.keySet());
        }
    }
}
