package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.common.admission.CapacityReport;
import self.research.ontology.common.admission.Estimates;
import self.research.ontology.common.admission.ReservationLedger;
import self.research.ontology.common.admission.WorkKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorCapacityCoexistenceTest {

    private static final long MIB = FakeMemorySignals.MIB;

    @Test
    void executeLoadsAndSparqlShareASmallCapacityWithoutExceedingShareOrTotal() throws Exception {
        FakeMemorySignals signals = new FakeMemorySignals();
        signals.heapMax = 400 * MIB;
        ReservationLedger ledger = signals.ledger(Map.of(
                "ontocode.capacity.headroom-fraction", "0.25",
                "ontocode.capacity.kind.execute-rules.max-active-per-owner", "1",
                "ontocode.capacity.kind.sparql-query.max-active-per-owner", "1"));
        HeavyWorkAdmission admission = new HeavyWorkAdmission(ledger, new Estimates());
        long capacity = ledger.capacityBytes();

        AtomicLong peakTotal = new AtomicLong();
        Map<String, Long> peakByKind = new ConcurrentHashMap<>();
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        int workers = 36;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        WorkKind[] kinds = {WorkKind.EXECUTE_RULES, WorkKind.REASONING, WorkKind.SPARQL_QUERY};
        long[] sizes = {40 * MIB, 50 * MIB, 32 * MIB};
        for (int i = 0; i < workers; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                for (int round = 0; round < 5; round++) {
                    WorkKind kind = kinds[(index + round) % kinds.length];
                    long size = sizes[(index + round) % kinds.length];
                    try (HeavyWorkAdmission.Hold hold = admission.admit("user-" + index, kind, size)) {
                        admitted.incrementAndGet();
                        CapacityReport report = ledger.capacityReport();
                        peakTotal.accumulateAndGet(report.reservedBytes(), Math::max);
                        for (CapacityReport.KindReport k : report.kinds()) {
                            peakByKind.merge(k.kind(), k.reservedBytes(), Math::max);
                            assertTrue(k.reservedBytes() <= k.shareCapBytes(),
                                    k.kind() + " " + k.reservedBytes() + " over its share " + k.shareCapBytes());
                        }
                        Thread.sleep(3);
                    } catch (BusyException busy) {
                        refused.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertTrue(admitted.get() > 0, "something must be admitted");
        assertTrue(refused.get() > 0, "the small capacity must refuse some of the offered work");
        assertTrue(peakTotal.get() <= capacity, "total " + peakTotal + " exceeded capacity " + capacity);
        assertEquals(0, ledger.reservedBytes());
        CapacityReport end = ledger.capacityReport();
        assertEquals(0, end.totals().activeCount());
        for (CapacityReport.KindReport k : end.kinds()) {
            assertEquals(0, k.reservedBytes(), k.kind());
            assertEquals(0, k.active(), k.kind());
        }
        assertEquals(admitted.get() + refused.get(), workers * 5);
    }
}
