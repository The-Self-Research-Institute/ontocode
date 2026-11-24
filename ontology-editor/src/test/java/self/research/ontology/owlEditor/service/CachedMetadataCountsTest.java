package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CachedMetadataCountsTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final AtomicReference<String> stamp = new AtomicReference<>("1");
    private final AtomicReference<String> scope = new AtomicReference<>("public");
    private final CachedMetadataCounts cache = new CachedMetadataCounts(now::get, p -> scope.get(), p -> stamp.get());

    @Test
    void secondCallWithSameStampReusesTheResult() {
        AtomicInteger runs = new AtomicInteger();
        assertEquals(1, (int) cache.get("p", "n", runs::incrementAndGet));
        assertEquals(1, (int) cache.get("p", "n", runs::incrementAndGet));
        assertEquals(1, runs.get());
    }

    @Test
    void aWriteChangesTheStampAndForcesARecompute() {
        AtomicInteger runs = new AtomicInteger();
        cache.get("p", "n", runs::incrementAndGet);
        stamp.set("2");
        assertEquals(2, (int) cache.get("p", "n", runs::incrementAndGet));
    }

    @Test
    void draftAndPublicScopesDoNotShareResults() {
        AtomicInteger runs = new AtomicInteger();
        cache.get("p", "n", runs::incrementAndGet);
        scope.set("draft|u1");
        cache.get("p", "n", runs::incrementAndGet);
        scope.set("draft|u2");
        cache.get("p", "n", runs::incrementAndGet);
        assertEquals(3, runs.get());
    }

    @Test
    void aResultMarkedFailedIsReturnedButNotKept() {
        AtomicInteger runs = new AtomicInteger();
        assertEquals(0, (int) cache.get("p", "n", () -> {
            runs.incrementAndGet();
            CachedMetadataCounts.markFailed();
            return 0;
        }));
        assertEquals(5, (int) cache.get("p", "n", () -> {
            runs.incrementAndGet();
            return 5;
        }));
        assertEquals(5, (int) cache.get("p", "n", runs::incrementAndGet));
        assertEquals(2, runs.get());
    }

    @Test
    void aFailureInsideAnOuterComputeStopsTheOuterResultBeingKept() {
        AtomicInteger runs = new AtomicInteger();
        cache.get("p", "outer", () -> {
            runs.incrementAndGet();
            return cache.get("p", "inner", () -> {
                CachedMetadataCounts.markFailed();
                return 0;
            });
        });
        cache.get("p", "outer", runs::incrementAndGet);
        assertEquals(2, runs.get());
    }

    @Test
    void theCacheStaysBoundedAndKeepsWorking() {
        AtomicInteger runs = new AtomicInteger();
        int total = CachedMetadataCounts.MAX_ENTRIES + 5;
        for (int i = 0; i < total; i++) {
            cache.get("p", "n" + i, runs::incrementAndGet);
        }
        assertEquals(total, runs.get());
        cache.get("p", "n" + (total - 1), runs::incrementAndGet);
        assertEquals(total, runs.get());
        cache.get("p", "n0", runs::incrementAndGet);
        assertEquals(total + 1, runs.get());
    }

    @Test
    void resultExpiresAfterTheTimeLimit() {
        AtomicInteger runs = new AtomicInteger();
        cache.get("p", "n", runs::incrementAndGet);
        now.addAndGet(CachedMetadataCounts.TTL_MS + 1);
        cache.get("p", "n", runs::incrementAndGet);
        assertEquals(2, runs.get());
    }

    @Test
    void missingStampIsNeverCached() {
        stamp.set(null);
        AtomicInteger runs = new AtomicInteger();
        cache.get("p", "n", runs::incrementAndGet);
        cache.get("p", "n", runs::incrementAndGet);
        assertEquals(2, runs.get());
    }

    @Test
    void twentyConcurrentCallersRunTheQueryOnce() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<Integer>> results = new ArrayList<>();
        results.add(pool.submit(() -> cache.get("p", "n", () -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return runs.incrementAndGet();
        })));
        started.await(10, TimeUnit.SECONDS);
        for (int i = 0; i < 19; i++) {
            results.add(pool.submit(() -> cache.get("p", "n", runs::incrementAndGet)));
        }
        Thread.sleep(300);
        release.countDown();
        for (Future<Integer> f : results) {
            assertEquals(1, (int) f.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, runs.get());
        pool.shutdownNow();
    }

    @Test
    void aResultThatSaysItsMetricsFailedIsReturnedButNeverKept() {
        AtomicInteger runs = new AtomicInteger();
        java.util.Map<String, Object> failed = java.util.Map.of("classCount", 0, "metricsFailed", true);

        cache.get("p", "n", () -> {
            runs.incrementAndGet();
            return failed;
        });
        cache.get("p", "n", () -> {
            runs.incrementAndGet();
            return java.util.Map.of("classCount", 9);
        });

        assertEquals(2, runs.get());
    }

    @Test
    void invalidatingAProjectDropsItsKeptResultsOnly() {
        AtomicInteger runs = new AtomicInteger();
        cache.get("p1", "n", runs::incrementAndGet);
        cache.get("p2", "n", runs::incrementAndGet);

        cache.invalidate("p1");
        cache.get("p1", "n", runs::incrementAndGet);
        cache.get("p2", "n", runs::incrementAndGet);

        assertEquals(3, runs.get());
    }
}
