package self.research.ontology.owlEditor.service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class CachedMetadataCounts {

    static final long TTL_MS = 60_000;
    static final int MAX_ENTRIES = 2_000;

    private record Entry(String stamp, Object value, long expiresAt) {}

    private static final ThreadLocal<boolean[]> FAILED = new ThreadLocal<>();

    static void markFailed() {
        boolean[] flag = FAILED.get();
        if (flag != null) {
            flag[0] = true;
        }
    }

    private final Map<String, Entry> cached = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Entry>> inFlight = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final Function<String, String> scopeOf;
    private final Function<String, String> stampOf;

    CachedMetadataCounts(LongSupplier clock, Function<String, String> scopeOf, Function<String, String> stampOf) {
        this.clock = clock;
        this.scopeOf = scopeOf;
        this.stampOf = stampOf;
    }

    static CachedMetadataCounts forService(SparqlDatasetService datasets, ProjectMetadataService metadata,
                                           ProjectImportService imports, StorageManager storage) {
        Function<String, String> draftUser = project -> {
            String user = SparqlQueryContext.getUserId();
            return user != null && !user.isBlank() && datasets.hasActiveDraftOverlay(project, user) ? user : null;
        };
        return new CachedMetadataCounts(System::currentTimeMillis,
                project -> {
                    String user = draftUser.apply(project);
                    return user == null ? "public" : "draft|" + user;
                },
                project -> {
                    String base = metadata.versionStamp(project);
                    if (base == null) {
                        return null;
                    }
                    String user = draftUser.apply(project);
                    String draft = user == null || storage == null ? "" : String.valueOf(storage.getDraftGraphVersion(project, user));
                    return base + "|" + draft + "|" + (imports != null && imports.isFusekiSyncPending(project));
                });
    }

    void invalidate(String projectId) {
        cached.keySet().removeIf(key -> key.startsWith(projectId + "|"));
    }

    @SuppressWarnings("unchecked")
    <V> V get(String projectId, String name, Supplier<V> compute) {
        String key = projectId + "|" + scopeOf.apply(projectId) + "|" + name;
        String stamp = stampOf.apply(projectId);
        if (stamp == null) {
            return compute.get();
        }
        Entry hit = cached.get(key);
        if (hit != null && hit.stamp().equals(stamp) && clock.getAsLong() < hit.expiresAt()) {
            return (V) hit.value();
        }
        CompletableFuture<Entry> mine = new CompletableFuture<>();
        CompletableFuture<Entry> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            Entry shared = null;
            try {
                shared = running.join();
            } catch (RuntimeException ignored) {
                shared = null;
            }
            if (shared != null && shared.stamp().equals(stamp)) {
                return (V) shared.value();
            }
            return compute.get();
        }
        Entry result = null;
        boolean[] flag = new boolean[1];
        boolean[] outer = FAILED.get();
        FAILED.set(flag);
        try {
            V fresh = compute.get();
            result = new Entry(stamp, fresh, clock.getAsLong() + TTL_MS);
            if (fresh instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("metricsFailed"))) {
                flag[0] = true;
            }
            if (!flag[0]) {
                if (cached.size() >= MAX_ENTRIES) {
                    long now = clock.getAsLong();
                    cached.values().removeIf(entry -> entry.expiresAt() <= now);
                    if (cached.size() >= MAX_ENTRIES) {
                        cached.clear();
                    }
                }
                cached.put(key, result);
            }
            return fresh;
        } finally {
            if (outer == null) {
                FAILED.remove();
            } else {
                FAILED.set(outer);
                outer[0] |= flag[0];
            }
            mine.complete(result);
            inFlight.remove(key, mine);
        }
    }
}
