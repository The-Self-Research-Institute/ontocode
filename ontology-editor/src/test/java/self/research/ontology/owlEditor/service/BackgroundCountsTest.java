package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundCountsTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final AtomicLong now = new AtomicLong(1_000);
    private final AtomicReference<String> stamp = new AtomicReference<>("v1");

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    private BackgroundCounts counts(long budgetMs) {
        return new BackgroundCounts(project -> stamp.get(), now::get, pool, budgetMs);
    }

    private static Map<String, Supplier<Map<String, Object>>> task(String name, Supplier<Map<String, Object>> compute) {
        Map<String, Supplier<Map<String, Object>>> tasks = new LinkedHashMap<>();
        tasks.put(name, compute);
        return tasks;
    }

    @Test
    void aQuickCountIsReturnedFreshInTheSameRequest() {
        Map<String, Object> out = counts(1_000).collect("p", task("a", () -> Map.of("annotationPropertyCount", 5)));

        assertEquals(5, out.get("annotationPropertyCount"));
        assertFalse(out.containsKey(BackgroundCounts.PENDING));
    }

    @Test
    void aSlowCountDoesNotHoldTheRequestAndIsServedOnceItFinishes() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        BackgroundCounts counts = counts(50);
        Map<String, Supplier<Map<String, Object>>> slow = task("a", () -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.countDown();
            return Map.of("annotationPropertyCount", 9);
        });

        long started = System.nanoTime();
        Map<String, Object> first = counts.collect("p", slow);
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(tookMs < 2_000, "took " + tookMs + "ms");
        assertEquals(true, first.get(BackgroundCounts.PENDING));
        assertFalse(first.containsKey("annotationPropertyCount"));
        release.countDown();
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertEquals(9, counts.collect("p", slow).get("annotationPropertyCount"));
    }

    @Test
    void manyRequestsAtOnceStartOnlyOneCountPerProject() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        BackgroundCounts counts = counts(20);
        Map<String, Supplier<Map<String, Object>>> slow = task("a", () -> {
            runs.incrementAndGet();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of("n", 1);
        });
        ExecutorService callers = Executors.newFixedThreadPool(8);
        try {
            Future<?>[] calls = new Future<?>[8];
            for (int i = 0; i < calls.length; i++) {
                calls[i] = callers.submit(() -> counts.collect("p", slow));
            }
            for (Future<?> call : calls) {
                call.get(5, TimeUnit.SECONDS);
            }
        } finally {
            release.countDown();
            callers.shutdownNow();
        }

        assertEquals(1, runs.get());
    }

    @Test
    void aFailedCountWaitsBeforeItIsTriedAgain() {
        AtomicInteger runs = new AtomicInteger();
        BackgroundCounts counts = counts(1_000);
        Map<String, Supplier<Map<String, Object>>> failing = task("a", () -> {
            runs.incrementAndGet();
            CachedMetadataCounts.markFailed();
            return Map.of("annotationPropertyCount", 0);
        });

        Map<String, Object> first = counts.collect("p", failing);
        counts.collect("p", failing);

        assertEquals(1, runs.get());
        assertEquals(true, first.get(BackgroundCounts.PENDING));
        assertFalse(first.containsKey("annotationPropertyCount"));
        now.addAndGet(BackgroundCounts.FAILURE_BACKOFF_MS + 1);
        counts.collect("p", failing);
        assertEquals(2, runs.get());
    }

    @Test
    void anEditServesTheLastNumberAsPendingUntilTheNewCountArrives() {
        CountDownLatch release = new CountDownLatch(1);
        BackgroundCounts counts = counts(300);
        assertEquals(3, counts.collect("p", task("a", () -> Map.of("n", 3))).get("n"));

        stamp.set("v2");
        Map<String, Object> afterEdit = counts.collect("p", task("a", () -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Map.of("n", 4);
        }));
        release.countDown();

        assertEquals(3, afterEdit.get("n"));
        assertEquals(true, afterEdit.get(BackgroundCounts.PENDING));
    }

    @Test
    void invalidateForcesARecountForThatProjectOnly() {
        AtomicInteger runs = new AtomicInteger();
        BackgroundCounts counts = counts(1_000);
        Map<String, Supplier<Map<String, Object>>> counting = task("a", () -> Map.of("n", runs.incrementAndGet()));

        counts.collect("p", counting);
        counts.collect("q", counting);
        counts.invalidate("p");
        counts.collect("p", counting);
        counts.collect("q", counting);

        assertEquals(3, runs.get());
    }

    @Test
    void aFullWorkerQueueLeavesTheRequestPendingWithoutAnError() {
        BackgroundCounts counts = new BackgroundCounts(project -> "v1", now::get, runnable -> {
            throw new java.util.concurrent.RejectedExecutionException("full");
        }, 1_000);

        Map<String, Object> out = counts.collect("p", task("a", () -> Map.of("n", 1)));

        assertEquals(true, out.get(BackgroundCounts.PENDING));
        assertFalse(out.containsKey("n"));
    }
}
