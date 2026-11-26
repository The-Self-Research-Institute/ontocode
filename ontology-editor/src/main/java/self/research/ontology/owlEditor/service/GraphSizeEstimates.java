package self.research.ontology.owlEditor.service;

import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

final class GraphSizeEstimates {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GraphSizeEstimates.class);

    static final long WAIT_MILLIS = 8_000;
    static final long EXACT_WAIT_MILLIS = 300_000;
    static final long UNKNOWN_TTL_MILLIS = 300_000;
    static final long REFRESH_AFTER_WRITE_MILLIS = 30_000;

    interface Counter {
        long count(String projectId) throws Exception;
    }

    private record Estimate(long triples, long atMillis, boolean stale) {
        boolean known() {
            return triples >= 0;
        }
    }

    private final ConcurrentHashMap<String, Estimate> estimates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Long>> counting = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> writes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> resets = new ConcurrentHashMap<>();
    private final ExecutorService pool;
    private final LongSupplier clock;
    private final long waitMillis;

    GraphSizeEstimates() {
        this(Executors.newFixedThreadPool(2, daemonThreads()), System::currentTimeMillis, WAIT_MILLIS);
    }

    GraphSizeEstimates(ExecutorService pool, LongSupplier clock, long waitMillis) {
        this.pool = pool;
        this.clock = clock;
        this.waitMillis = waitMillis;
    }

    long estimate(String projectId, Counter counter) {
        Estimate current = estimates.get(projectId);
        long now = clock.getAsLong();
        if (current != null && current.known()) {
            if (current.stale() && now - current.atMillis() >= REFRESH_AFTER_WRITE_MILLIS) {
                start(projectId, counter);
            }
            return current.triples();
        }
        if (current != null && now - current.atMillis() < UNKNOWN_TTL_MILLIS) {
            return -1;
        }
        return await(projectId, start(projectId, counter), waitMillis);
    }

    long exact(String projectId, Counter counter) {
        Estimate current = estimates.get(projectId);
        if (current != null && current.known() && !current.stale()) {
            return current.triples();
        }
        return await(projectId, start(projectId, counter), EXACT_WAIT_MILLIS);
    }

    boolean looksLarge(String projectId, long threshold) {
        Estimate current = estimates.get(projectId);
        if (current == null) {
            return false;
        }
        if (current.known()) {
            return current.triples() > threshold;
        }
        return clock.getAsLong() - current.atMillis() < UNKNOWN_TTL_MILLIS;
    }

    OptionalLong known(String projectId) {
        Estimate current = estimates.get(projectId);
        return current != null && current.known() ? OptionalLong.of(current.triples()) : OptionalLong.empty();
    }

    static long storedTriples(java.util.Map<String, Object> metadata) {
        if (metadata == null) {
            return -1;
        }
        if (metadata.get("tripleCount") instanceof Number n && n.longValue() > 0) {
            return n.longValue();
        }
        if (metadata.get("counts") instanceof java.util.Map<?, ?> counts && counts.get("triples") instanceof Number n) {
            return n.longValue();
        }
        return -1;
    }

    void seed(String projectId, long triples) {
        if (projectId != null && triples > 0) {
            estimates.put(projectId, new Estimate(triples, clock.getAsLong(), false));
        }
    }

    void noteWrite(String projectId) {
        long now = clock.getAsLong();
        writes.merge(projectId, 1L, Long::sum);
        estimates.computeIfPresent(projectId, (id, current) ->
                current.known() ? new Estimate(current.triples(), now, true) : null);
    }

    void reset(String projectId) {
        resets.merge(projectId, 1L, Long::sum);
        estimates.remove(projectId);
        writes.remove(projectId);
        counting.remove(projectId);
    }

    private CompletableFuture<Long> start(String projectId, Counter counter) {
        return counting.compute(projectId, (id, running) ->
                running != null && !running.isDone() ? running : launch(id, counter));
    }

    private CompletableFuture<Long> launch(String projectId, Counter counter) {
        long writesAtStart = writes.getOrDefault(projectId, 0L);
        long resetsAtStart = resets.getOrDefault(projectId, 0L);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return counter.count(projectId);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, pool).whenComplete((triples, error) -> {
            boolean resetSince = resets.getOrDefault(projectId, 0L) != resetsAtStart;
            if (error == null && triples != null && triples >= 0 && !resetSince) {
                boolean writtenSince = writes.getOrDefault(projectId, 0L) != writesAtStart;
                estimates.put(projectId, new Estimate(triples, clock.getAsLong(), writtenSince));
            }
        });
    }

    private long await(String projectId, CompletableFuture<Long> future, long waitMillis) {
        try {
            return future.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("[GraphSize] Counting project {} took over {} ms; treating its size as unknown", projectId, waitMillis);
            rememberUnknown(projectId);
            return -1;
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("[GraphSize] Could not count project {}: {}", projectId, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            rememberUnknown(projectId);
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private void rememberUnknown(String projectId) {
        estimates.compute(projectId, (id, current) ->
                current != null && current.known() ? current : new Estimate(-1, clock.getAsLong(), false));
    }

    private static java.util.concurrent.ThreadFactory daemonThreads() {
        AtomicInteger n = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "graph-size-" + n.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
