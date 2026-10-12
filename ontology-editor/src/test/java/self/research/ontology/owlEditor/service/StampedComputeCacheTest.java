package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StampedComputeCacheTest {

    private final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000);
    private final StampedComputeCache<Integer> cache = new StampedComputeCache<>(now::get);

    @Test
    void reusesValueWhileStampIsUnchanged() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(1, cache.get("p", "v1", calls::incrementAndGet, 0));
        assertEquals(1, cache.get("p", "v1", calls::incrementAndGet, 0));
        assertEquals(1, calls.get());
    }

    @Test
    void recomputesWhenStampChanges() {
        AtomicInteger calls = new AtomicInteger();
        cache.get("p", "v1", calls::incrementAndGet, 0);
        assertEquals(2, cache.get("p", "v2", calls::incrementAndGet, 0));
    }

    @Test
    void neverCachesWithoutStamp() {
        AtomicInteger calls = new AtomicInteger();
        cache.get("p", null, calls::incrementAndGet, 0);
        cache.get("p", null, calls::incrementAndGet, 0);
        assertEquals(2, calls.get());
    }

    @Test
    void fallbackWhenComputeReturnsNullAndNotRetriedForSameStamp() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(-1, cache.get("p", "v1", () -> {
            calls.incrementAndGet();
            return null;
        }, -1));
        assertEquals(-1, cache.get("p", "v1", calls::incrementAndGet, -1));
        assertEquals(1, calls.get());
    }

    @Test
    void failureIsRetriedAfterTtlAndSuccessReplacesIt() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(-1, cache.get("p", "v1", () -> null, -1));
        now.addAndGet(31_000);
        assertEquals(7, cache.get("p", "v1", () -> {
            calls.incrementAndGet();
            return 7;
        }, -1));
        now.addAndGet(31_000);
        assertEquals(7, cache.get("p", "v1", calls::incrementAndGet, -1));
        assertEquals(1, calls.get());
    }

    @Test
    void concurrentCallersShareOneComputation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> cache.get("p", "v1", () -> {
                started.countDown();
                await(release);
                return calls.incrementAndGet();
            }, 0));
            started.await(5, TimeUnit.SECONDS);
            Future<Integer> second = pool.submit(() -> cache.get("p", "v1", calls::incrementAndGet, 0));
            Thread.sleep(100);
            release.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS));
            assertEquals(1, second.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
