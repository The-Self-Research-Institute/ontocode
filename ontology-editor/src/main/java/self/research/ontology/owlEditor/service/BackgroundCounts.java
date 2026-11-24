package self.research.ontology.owlEditor.service;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class BackgroundCounts {

    static final long BUDGET_MS = 1_500;
    static final long FRESH_MS = 10 * 60_000L;
    static final long FAILURE_BACKOFF_MS = 5 * 60_000L;
    static final int MAX_ENTRIES = 2_000;
    static final String PENDING = "countsPending";

    private record Entry(String stamp, Map<String, Object> values, long at) {}

    private final Map<String, Entry> latest = new ConcurrentHashMap<>();
    private final Map<String, Long> failedUntil = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Map<String, Object>>> running = new ConcurrentHashMap<>();
    private final Function<String, String> stampOf;
    private final LongSupplier clock;
    private final Executor executor;
    private final long budgetMs;

    BackgroundCounts(Function<String, String> stampOf, LongSupplier clock, Executor executor, long budgetMs) {
        this.stampOf = stampOf;
        this.clock = clock;
        this.executor = executor;
        this.budgetMs = budgetMs;
    }

    static BackgroundCounts forService(ProjectMetadataService metadata, ProjectImportService imports) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), runnable -> {
            Thread thread = new Thread(runnable, "metadata-counts");
            thread.setDaemon(true);
            return thread;
        });
        pool.allowCoreThreadTimeOut(true);
        return new BackgroundCounts(project -> metadata.versionStamp(project) + "|"
                + (imports != null && imports.isFusekiSyncPending(project)), System::currentTimeMillis, pool, BUDGET_MS);
    }

    void invalidate(String projectId) {
        latest.replaceAll((key, entry) -> key.startsWith(projectId + "|") ? new Entry("", entry.values(), entry.at()) : entry);
    }

    Map<String, Object> collect(String projectId, Map<String, Supplier<Map<String, Object>>> tasks) {
        String stamp = String.valueOf(stampOf.apply(projectId));
        long deadline = clock.getAsLong() + budgetMs;
        Map<String, Object> out = new HashMap<>();
        Map<String, CompletableFuture<Map<String, Object>>> waiting = new LinkedHashMap<>();
        for (Map.Entry<String, Supplier<Map<String, Object>>> task : tasks.entrySet()) {
            String key = projectId + "|" + task.getKey();
            Entry hit = latest.get(key);
            if (hit != null && hit.stamp().equals(stamp) && clock.getAsLong() - hit.at() < FRESH_MS) {
                out.putAll(hit.values());
            } else {
                waiting.put(key, start(key, stamp, task.getValue()));
            }
        }
        boolean pending = false;
        for (Map.Entry<String, CompletableFuture<Map<String, Object>>> wait : waiting.entrySet()) {
            Map<String, Object> fresh = await(wait.getValue(), deadline);
            if (fresh != null) {
                out.putAll(fresh);
                continue;
            }
            pending = true;
            Entry stale = latest.get(wait.getKey());
            if (stale != null) {
                out.putAll(stale.values());
            }
        }
        if (pending) {
            out.put(PENDING, true);
        }
        return out;
    }

    private CompletableFuture<Map<String, Object>> start(String key, String stamp, Supplier<Map<String, Object>> compute) {
        String flight = key + "|" + stamp;
        Long blocked = failedUntil.get(flight);
        if (blocked != null && clock.getAsLong() < blocked) {
            return null;
        }
        CompletableFuture<Map<String, Object>> mine = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> existing = running.putIfAbsent(flight, mine);
        if (existing != null) {
            return existing;
        }
        try {
            executor.execute(() -> run(key, stamp, flight, compute, mine));
        } catch (RejectedExecutionException full) {
            running.remove(flight, mine);
            mine.complete(null);
            return null;
        }
        return mine;
    }

    private void run(String key, String stamp, String flight, Supplier<Map<String, Object>> compute,
                     CompletableFuture<Map<String, Object>> mine) {
        Map<String, Object> result = null;
        try {
            boolean[] failed = new boolean[1];
            Map<String, Object> fresh = CachedMetadataCounts.tracked(compute, failed);
            if (!failed[0] && fresh != null && !Boolean.TRUE.equals(fresh.get("metricsFailed"))) {
                result = Collections.unmodifiableMap(new HashMap<>(fresh));
                bound(latest);
                latest.put(key, new Entry(stamp, result, clock.getAsLong()));
                failedUntil.remove(flight);
            } else {
                bound(failedUntil);
                failedUntil.put(flight, clock.getAsLong() + FAILURE_BACKOFF_MS);
            }
        } catch (RuntimeException e) {
            bound(failedUntil);
            failedUntil.put(flight, clock.getAsLong() + FAILURE_BACKOFF_MS);
        } finally {
            running.remove(flight, mine);
            mine.complete(result);
        }
    }

    private Map<String, Object> await(CompletableFuture<Map<String, Object>> future, long deadline) {
        if (future == null) {
            return null;
        }
        try {
            return future.get(Math.max(0, deadline - clock.getAsLong()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            return null;
        }
    }

    private static void bound(Map<String, ?> map) {
        if (map.size() >= MAX_ENTRIES) {
            map.clear();
        }
    }
}
