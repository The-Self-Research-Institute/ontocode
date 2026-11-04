package self.research.ontology.common.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import self.research.ontology.common.admission.Fakes.FakeClock;
import self.research.ontology.common.admission.Fakes.FakeSignals;

class ReservationLedgerTest {

    private final FakeSignals signals = new FakeSignals();
    private final FakeClock clock = new FakeClock();

    private ReservationLedger ledger(AdmissionProperties props) {
        return new ReservationLedger(signals, props, () -> 0L, clock);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("condition not reached");
            }
            Thread.sleep(2);
        }
    }

    @Test
    void concurrentTryReserveNeverExceedsCapacityAndEndsAtZero() throws Exception {
        ReservationLedger ledger = ledger(Fakes.props(1, 0, 0));
        long capacity = ledger.capacityBytes();
        assertEquals(1000, capacity);
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicLong held = new AtomicLong();
        AtomicLong maxHeld = new AtomicLong();
        AtomicLong admitted = new AtomicLong();
        try {
            for (int iteration = 0; iteration < 300; iteration++) {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    String owner = "owner-" + t;
                    futures.add(pool.submit(() -> {
                        start.await();
                        AdmissionResult result = ledger.tryReserve(owner, "load", 100);
                        if (result.isAdmitted()) {
                            admitted.incrementAndGet();
                            long now = held.addAndGet(100);
                            maxHeld.accumulateAndGet(now, Math::max);
                            Thread.yield();
                            held.addAndGet(-100);
                            result.reservation().release();
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
                assertEquals(0, ledger.reservedBytes());
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(maxHeld.get() <= capacity, "max held " + maxHeld.get());
        assertTrue(admitted.get() > 0);
        LedgerStats.Snapshot s = ledger.stats().snapshot();
        assertEquals(0, s.activeCount());
        assertTrue(s.peakReservedBytes() <= capacity);
        assertEquals(s.offered(), s.admitted() + s.refusedTotal());
        assertEquals(s.admitted(), s.completed());
    }

    @Test
    void concurrentBlockingAcquireAllCompleteWithinCapacity() throws Exception {
        ReservationLedger ledger = ledger(Fakes.props(1, 1, 100));
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicLong held = new AtomicLong();
        AtomicLong maxHeld = new AtomicLong();
        AtomicLong done = new AtomicLong();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                String owner = "o" + t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 20; i++) {
                        AdmissionResult r = ledger.acquire(owner, "load", 300, Duration.ofSeconds(20));
                        assertTrue(r.isAdmitted(), String.valueOf(r.reason()));
                        long now = held.addAndGet(300);
                        maxHeld.accumulateAndGet(now, Math::max);
                        held.addAndGet(-300);
                        r.reservation().release();
                        done.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(threads * 20L, done.get());
        assertTrue(maxHeld.get() <= 1000);
        assertEquals(0, ledger.reservedBytes());
        assertEquals(0, ledger.stats().snapshot().waitingNow());
    }

    @Test
    void headAfterGcPressureAndContainerPressureFlipAdmission() {
        ReservationLedger ledger = ledger(Fakes.props(5, 0, 0));
        assertTrue(ledger.tryReserve("a", "load", 10).isAdmitted());
        signals.afterGc = 800;
        AdmissionResult heap = ledger.tryReserve("a", "load", 10);
        assertEquals(RefusalReason.HEAP_PRESSURE, heap.reason());
        assertTrue(heap.retryAfterMillis() > 0);
        assertFalse(heap.message().isBlank());
        signals.afterGc = 500;
        assertTrue(ledger.tryReserve("a", "load", 10).isAdmitted());
        signals.limit = MemorySignals.Metric.available(2000);
        signals.usage = MemorySignals.Metric.available(1800);
        assertEquals(RefusalReason.CONTAINER_PRESSURE, ledger.tryReserve("a", "load", 10).reason());
        signals.usage = MemorySignals.Metric.available(1000);
        assertTrue(ledger.tryReserve("a", "load", 10).isAdmitted());
        signals.usage = MemorySignals.Metric.unavailable();
        assertTrue(ledger.tryReserve("a", "load", 10).isAdmitted());
        signals.usage = MemorySignals.Metric.available(1800);
        signals.limit = MemorySignals.Metric.unlimited();
        assertTrue(ledger.tryReserve("a", "load", 10).isAdmitted());
    }

    @Test
    void afterGcUnavailableFallsBackToHeapUsed() {
        ReservationLedger ledger = ledger(Fakes.props(5, 0, 0));
        signals.afterGcAvailable = false;
        signals.heapUsed = 900;
        assertEquals(RefusalReason.HEAP_PRESSURE, ledger.tryReserve("a", "load", 10).reason());
        signals.heapUsed = 100;
        assertTrue(ledger.tryReserve("a", "load", 10).isAdmitted());
    }

    @Test
    void estimateOverrunDoesNotChangeAccountingAndShowsOnlyThroughPressure() {
        ReservationLedger ledger = ledger(Fakes.props(5, 0, 0));
        Reservation r = ledger.tryReserve("a", "load", 100).reservation();
        signals.heapUsed = 950;
        signals.afterGc = 900;
        assertEquals(100, ledger.reservedBytes());
        assertEquals(900, ledger.availableBytes());
        assertEquals(RefusalReason.HEAP_PRESSURE, ledger.tryReserve("b", "load", 10).reason());
        r.release();
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void releaseIsExactlyOnceAndCountsDoubleReleases() {
        ReservationLedger ledger = ledger(Fakes.props(5, 0, 0));
        Reservation r = ledger.tryReserve("a", "load", 100).reservation();
        assertTrue(r.release());
        assertFalse(r.release());
        r.close();
        LedgerStats.Snapshot s = ledger.stats().snapshot();
        assertEquals(2, s.doubleReleaseAttempts());
        assertEquals(1, s.completed());
        assertEquals(0, s.reservedBytes());
        assertEquals(Reservation.State.RELEASED, r.state());
    }

    @Test
    void releaseOnExceptionAndTryWithResources() {
        ReservationLedger ledger = ledger(Fakes.props(5, 0, 0));
        assertThrows(IllegalStateException.class, () -> {
            try (Reservation r = ledger.tryReserve("a", "load", 100).reservation()) {
                assertEquals(100, ledger.reservedBytes());
                throw new IllegalStateException("boom");
            }
        });
        assertEquals(0, ledger.reservedBytes());
        Reservation r = ledger.tryReserve("a", "load", 100).reservation();
        try {
            throw new RuntimeException("x");
        } catch (RuntimeException e) {
            r.release();
        } finally {
            r.release();
        }
        assertEquals(0, ledger.reservedBytes());
        assertEquals(1, ledger.stats().snapshot().doubleReleaseAttempts());
    }

    @Test
    void ownerLimitsApplyToActiveAndWaiting() throws Exception {
        ReservationLedger ledger = ledger(Fakes.props(1, 1, 10));
        Reservation first = ledger.tryReserve("u", "load", 600).reservation();
        assertEquals(RefusalReason.OWNER_LIMIT, ledger.tryReserve("u", "load", 10).reason());
        assertTrue(ledger.tryReserve("v", "load", 400).isAdmitted());
        assertTrue(ledger.tryReserve("w", "load", 10).isRefused());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<AdmissionResult> waiting = pool.submit(() -> ledger.acquire("w", "load", 500, Duration.ofSeconds(5)));
            waitUntil(() -> ledger.stats().snapshot().waitingNow() == 1);
            AdmissionResult second = ledger.acquire("w", "load", 500, Duration.ofSeconds(5));
            assertEquals(RefusalReason.OWNER_LIMIT, second.reason());
            first.release();
            assertTrue(waiting.get(5, TimeUnit.SECONDS).isAdmitted());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void fifoFairnessAndNoLostWakeup() throws Exception {
        ReservationLedger ledger = ledger(Fakes.props(1, 1, 10));
        Reservation holder = ledger.tryReserve("h", "load", 900).reservation();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<AdmissionResult> a = pool.submit(() -> ledger.acquire("A", "load", 600, Duration.ofSeconds(10)));
            waitUntil(() -> ledger.stats().snapshot().waitingNow() == 1);
            Future<AdmissionResult> b = pool.submit(() -> ledger.acquire("B", "load", 600, Duration.ofSeconds(10)));
            waitUntil(() -> ledger.stats().snapshot().waitingNow() == 2);
            AdmissionResult barge = ledger.tryReserve("C", "load", 100);
            assertTrue(barge.isRefused());
            holder.release();
            AdmissionResult ra = a.get(5, TimeUnit.SECONDS);
            assertTrue(ra.isAdmitted());
            assertFalse(b.isDone());
            ra.reservation().release();
            assertTrue(b.get(5, TimeUnit.SECONDS).isAdmitted());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void waitTimeoutAndQueueFullAndTooLarge() throws Exception {
        ReservationLedger ledger = ledger(Fakes.props(1, 1, 1));
        ledger.tryReserve("h", "load", 1000);
        AdmissionResult timeout = ledger.acquire("a", "load", 100, Duration.ofMillis(60));
        assertEquals(RefusalReason.WAIT_TIMEOUT, timeout.reason());
        assertEquals(0, ledger.stats().snapshot().waitingNow());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AdmissionResult> waiting = pool.submit(() -> ledger.acquire("b", "load", 100, Duration.ofSeconds(5)));
            waitUntil(() -> ledger.stats().snapshot().waitingNow() == 1);
            assertEquals(RefusalReason.QUEUE_FULL, ledger.acquire("c", "load", 100, Duration.ofSeconds(5)).reason());
            waiting.cancel(true);
        } finally {
            pool.shutdownNow();
        }
        long begin = System.nanoTime();
        AdmissionResult tooLarge = ledger.acquire("d", "load", 5000, Duration.ofSeconds(5));
        assertEquals(RefusalReason.TOO_LARGE, tooLarge.reason());
        assertTrue(System.nanoTime() - begin < TimeUnit.SECONDS.toNanos(1));
        assertEquals(RefusalReason.INVALID, ledger.tryReserve("d", "load", 0).reason());
        assertEquals(RefusalReason.INVALID, ledger.tryReserve("", "load", 5).reason());
        LedgerStats.Snapshot s = ledger.stats().snapshot();
        assertTrue(s.refusedByReason().get(RefusalReason.WAIT_TIMEOUT) >= 1);
        assertTrue(s.waitCount() >= 1);
        assertTrue(s.waitMaxNanos() > 0);
    }

    @Test
    void cacheAccountKeepsEvictedButBorrowedBytesCountedUntilDisposal() {
        ReservationLedger ledger = ledger(Fakes.props(1, 0, 0));
        CacheAccount cache = ledger.cacheAccount("ontology-cache");
        assertEquals(1000, ledger.capacityBytes());
        cache.add(300);
        assertEquals(700, ledger.capacityBytes());
        assertEquals(RefusalReason.TOO_LARGE, ledger.tryReserve("a", "load", 800).reason());
        assertEquals(300, ledger.baselineBytes());
        cache.remove(300);
        assertEquals(1000, ledger.capacityBytes());
        cache.remove(50);
        assertEquals(1, cache.underflowCount());
        assertEquals(0, cache.bytes());
    }

    @Test
    void baselineSupplierReducesCapacityWithHeadroom() {
        AdmissionProperties p = new AdmissionProperties(0.25, 0.75, 0.85, Duration.ofSeconds(1), 1, 1, 1,
                Duration.ofMinutes(1));
        ReservationLedger ledger = new ReservationLedger(signals, p, () -> 200L, clock);
        assertEquals(600, ledger.capacityBytes());
    }

    @Test
    void leaksAreReportedAndNeverForceReleased() {
        ReservationLedger ledger = ledger(Fakes.props(2, 0, 0));
        Reservation r = ledger.tryReserve("slow", "import", 100).reservation();
        clock.advance(Duration.ofMinutes(10));
        Reservation young = ledger.tryReserve("fast", "import", 100).reservation();
        clock.advance(Duration.ofMinutes(25));
        List<ReservationLedger.Leak> leaks = ledger.leaks(Duration.ofMinutes(30));
        assertEquals(1, leaks.size());
        assertEquals("slow", leaks.get(0).owner());
        assertEquals("import", leaks.get(0).kind());
        assertEquals(Duration.ofMinutes(35), leaks.get(0).age());
        AtomicReference<List<ReservationLedger.Leak>> seen = new AtomicReference<>();
        LedgerWatchdog dog = new LedgerWatchdog(ledger, Duration.ofMinutes(30), seen::set);
        assertEquals(1, dog.checkOnce().size());
        assertNotNull(seen.get());
        assertEquals(200, ledger.reservedBytes());
        assertTrue(r.isActive());
        r.release();
        young.release();
        seen.set(null);
        assertTrue(dog.checkOnce().isEmpty());
        assertNull(seen.get());
    }

    @Test
    void budgetContextPropagatesThroughExecutorAndBlocksNestedWaiting() throws Exception {
        ReservationLedger ledger = ledger(Fakes.props(1, 1, 5));
        ExecutorService raw = Executors.newFixedThreadPool(2);
        ContextPropagatingExecutor executor = new ContextPropagatingExecutor(raw);
        try (Reservation parent = ledger.tryReserve("u", "load", 100).reservation();
                BudgetContext.Scope scope = BudgetContext.enter(parent)) {
            Reservation seen = executor.submit(() -> BudgetContext.current().orElse(null)).get(5, TimeUnit.SECONDS);
            assertEquals(parent, seen);
            Reservation viaWrap = raw.submit(BudgetContext.wrap(() -> BudgetContext.current().orElse(null)))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(parent, viaWrap);
            assertNull(raw.submit(() -> BudgetContext.current().orElse(null)).get(5, TimeUnit.SECONDS));
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ledger.acquire("u", "load", 10, Duration.ofSeconds(1)));
            assertEquals("nested blocking acquire", e.getMessage());
            AdmissionResult nested = ledger.tryReserve("someone-else", "load", 50);
            assertTrue(nested.isAdmitted());
            assertEquals("u", nested.reservation().owner());
            assertEquals(parent.id(), nested.reservation().parentId());
            assertEquals(1, ledger.stats().snapshot().nestedAdmitted());
            assertEquals(150, ledger.reservedBytes());
            nested.reservation().release();
        } finally {
            raw.shutdownNow();
        }
        assertFalse(BudgetContext.holdsReservation());
        assertEquals(0, ledger.reservedBytes());
        assertTrue(ledger.acquire("u", "load", 10, Duration.ofSeconds(1)).isAdmitted());
    }

    @Test
    void releasedContextReservationNoLongerCountsAsHolding() {
        ReservationLedger ledger = ledger(Fakes.props(1, 1, 5));
        Reservation r = ledger.tryReserve("u", "load", 100).reservation();
        try (BudgetContext.Scope scope = BudgetContext.enter(r)) {
            r.release();
            assertFalse(BudgetContext.holdsReservation());
            AdmissionResult again = ledger.acquire("u", "load", 10, Duration.ofSeconds(1));
            assertTrue(again.isAdmitted());
            again.reservation().release();
        }
    }
}
