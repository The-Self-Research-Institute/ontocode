package self.research.ontology.common.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import org.junit.jupiter.api.Test;
import self.research.ontology.common.admission.Fakes.FakeClock;
import self.research.ontology.common.admission.Fakes.FakeSignals;

class CapacityCoexistenceTest {

    private final FakeSignals signals = new FakeSignals();
    private final FakeClock clock = new FakeClock();

    private ReservationLedger ledger() {
        signals.heapMax = 10_000;
        return new ReservationLedger(signals, CapacityConfig.defaults(), () -> 0L, clock);
    }

    @Test
    void kindCannotExceedItsShareAndDoesNotStarveOthers() throws Exception {
        ReservationLedger ledger = ledger();
        assertEquals(7500, ledger.capacityBytes());
        Reservation rules = ledger.tryReserve("a", WorkKind.EXECUTE_RULES, 3000).reservation();
        assertNotNull(rules);
        AdmissionResult more = ledger.tryReserve("b", WorkKind.EXECUTE_RULES, 100);
        assertEquals(RefusalReason.KIND_SHARE_EXCEEDED, more.reason());
        assertTrue(ledger.tryReserve("c", WorkKind.SPARQL_QUERY, 1800).isAdmitted());
        assertEquals(RefusalReason.TOO_LARGE, ledger.tryReserve("d", WorkKind.SPARQL_QUERY, 1900).reason());
        assertTrue(ledger.tryReserve("e", WorkKind.REASONING, 2500).isAdmitted());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AdmissionResult> waiting = pool.submit(() -> ledger.acquire("b", WorkKind.EXECUTE_RULES, 100,
                    Duration.ofSeconds(10)));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (ledger.stats().snapshot().waitingNow() < 1 && System.nanoTime() < end) {
                Thread.sleep(2);
            }
            assertEquals(1, ledger.stats().snapshot().waitingNow());
            AdmissionResult other = ledger.tryReserve("f", WorkKind.IMPORT_PARSE, 100);
            assertTrue(other.isAdmitted(), "a share-blocked waiter must not block other kinds");
            assertFalse(waiting.isDone());
            rules.release();
            assertTrue(waiting.get(5, TimeUnit.SECONDS).isAdmitted());
        } finally {
            pool.shutdownNow();
        }
        LedgerStats.Snapshot s = ledger.stats().snapshot();
        LedgerStats.KindSnapshot execute = s.byKind().get("EXECUTE_RULES");
        assertEquals(1, execute.refusedByReason().get(RefusalReason.KIND_SHARE_EXCEEDED));
        assertTrue(s.reservedBytes() <= ledger.capacityBytes());
    }

    @Test
    void globalCapacityStillBindsWhenSharesWouldAllowMore() {
        ReservationLedger ledger = ledger();
        assertTrue(ledger.tryReserve("a", WorkKind.REASONING, 3000).isAdmitted());
        assertTrue(ledger.tryReserve("b", WorkKind.EXECUTE_RULES, 3000).isAdmitted());
        assertEquals(RefusalReason.OVER_CAPACITY, ledger.tryReserve("c", WorkKind.IMPORT_PARSE, 2000).reason());
        assertTrue(ledger.tryReserve("c", WorkKind.IMPORT_PARSE, 1500).isAdmitted());
        assertEquals(7500, ledger.reservedBytes());
    }

    @Test
    void mixedKindsRunningConcurrentlyRespectKindCapsAndTotalCapacity() throws Exception {
        ReservationLedger ledger = ledger();
        long capacity = ledger.capacityBytes();
        WorkKind[] kinds = {WorkKind.IMPORT_PARSE, WorkKind.REASONING, WorkKind.EXECUTE_RULES,
                WorkKind.INFERENCE_BUILD, WorkKind.SPARQL_QUERY};
        long[] caps = new long[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            caps[i] = (long) (capacity * ledger.config().kind(kinds[i]).maxShare());
        }
        AtomicLongArray heldByKind = new AtomicLongArray(kinds.length);
        AtomicLong held = new AtomicLong();
        AtomicLong maxTotal = new AtomicLong();
        AtomicLongArray maxByKind = new AtomicLongArray(kinds.length);
        AtomicLongArray admittedByKind = new AtomicLongArray(kinds.length);
        int threads = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 200; round++) {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    int k = t % kinds.length;
                    String owner = "u" + t;
                    futures.add(pool.submit(() -> {
                        start.await();
                        AdmissionResult r = ledger.tryReserve(owner, kinds[k], 600);
                        if (r.isAdmitted()) {
                            admittedByKind.incrementAndGet(k);
                            long kindNow = heldByKind.addAndGet(k, 600);
                            long total = held.addAndGet(600);
                            maxByKind.accumulateAndGet(k, kindNow, Math::max);
                            maxTotal.accumulateAndGet(total, Math::max);
                            Thread.yield();
                            held.addAndGet(-600);
                            heldByKind.addAndGet(k, -600);
                            r.reservation().release();
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(maxTotal.get() <= capacity, "total " + maxTotal.get());
        for (int i = 0; i < kinds.length; i++) {
            assertTrue(maxByKind.get(i) <= caps[i], kinds[i] + " peaked at " + maxByKind.get(i));
            assertTrue(admittedByKind.get(i) > 0, kinds[i] + " was starved");
        }
        assertEquals(0, ledger.reservedBytes());
        LedgerStats.Snapshot s = ledger.stats().snapshot();
        assertEquals(s.offered(), s.admitted() + s.refusedTotal());
    }

    @Test
    void reportShowsKindsCachesSignalsConfigAndOldReservations() {
        ReservationLedger ledger = ledger();
        ledger.cacheAccount("ontology-cache").add(1000);
        Reservation old = ledger.tryReserve("slow", WorkKind.IMPORT_PARSE, 500).reservation();
        clock.advance(Duration.ofMinutes(45));
        ledger.tryReserve("x", WorkKind.EXECUTE_RULES, 100);
        ledger.tryReserve("x", WorkKind.EXECUTE_RULES, 100);
        signals.limit = MemorySignals.Metric.unlimited();
        CapacityReport report = ledger.capacityReport();
        assertEquals(6750, report.capacityBytes());
        assertEquals(1000, report.baselineBytes());
        assertEquals(600, report.reservedBytes());
        assertEquals(1, report.oldReservations().size());
        assertEquals("slow", report.oldReservations().get(0).owner());
        assertEquals(WorkKind.values().length, report.kinds().size());
        CapacityReport.KindReport execute = report.kinds().stream()
                .filter(k -> k.kind().equals("EXECUTE_RULES")).findFirst().orElseThrow();
        assertEquals(1, execute.active());
        assertEquals(100, execute.reservedBytes());
        assertEquals(1, execute.counters().refusedByReason().get(RefusalReason.OWNER_LIMIT));
        assertEquals(0.40, execute.shareCapFraction());
        assertEquals(2700, execute.shareCapBytes());
        assertEquals(100.0 / 2700, execute.shareUsedOfCap(), 1e-9);

        Map<String, Object> map = report.toMap();
        for (String key : List.of("capacityBytes", "baselineBytes", "reservedBytes", "kinds", "cacheAccounts",
                "signals", "config", "configSources", "oldReservations", "totals")) {
            assertTrue(map.containsKey(key), key);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> sig = (Map<String, Object>) map.get("signals");
        @SuppressWarnings("unchecked")
        Map<String, Object> limit = (Map<String, Object>) sig.get("containerLimit");
        assertEquals("UNLIMITED", limit.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> mapped = (Map<String, Object>) sig.get("mappedBufferBytes");
        assertEquals("UNAVAILABLE", mapped.get("status"));
        assertEquals(null, mapped.get("bytes"));
        @SuppressWarnings("unchecked")
        Map<String, Long> caches = (Map<String, Long>) map.get("cacheAccounts");
        assertEquals(1000L, caches.get("ontology-cache"));

        String table = report.renderTable();
        assertTrue(table.contains("EXECUTE_RULES"));
        assertTrue(table.contains("ontology-cache"));
        assertTrue(table.contains("slow"));
        assertTrue(table.contains("headroom-fraction=0.25"));
        assertTrue(table.contains("[default]"));
        assertTrue(table.contains("UNLIMITED"));
        old.release();
    }
}
