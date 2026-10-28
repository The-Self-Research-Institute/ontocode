package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.common.admission.ReservationLedger;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparqlUpdateAdmissionTest {

    private static final String G = "http://ontocode.org/project/p1";

    private FakeMemorySignals signals;
    private ReservationLedger ledger;
    private HeavyWorkAdmission admission;
    private final SparqlUpdateLimits limits = SparqlUpdateLimits.defaults();

    @BeforeEach
    void setUp() {
        signals = new FakeMemorySignals();
        ledger = signals.ledger();
        admission = new HeavyWorkAdmission(ledger, new self.research.ontology.common.admission.Estimates());
    }

    private static String insertOf(int triples) {
        StringBuilder sb = new StringBuilder("PREFIX : <http://x/>\nINSERT DATA {\n");
        for (int i = 0; i < triples; i++) {
            sb.append(":s").append(i).append(" :p :o").append(i).append(" .\n");
        }
        return sb.append("}").toString();
    }

    private String scope(String text) {
        return SparqlUpdateScoper.scope(text, G, List.of(), null, limits, admission, "u1");
    }

    @Test
    void aHugeUpdateIsRefusedBeforeAnyParsingOrReservation() {
        String huge = "x".repeat((int) limits.maxBytes() + 1);
        long start = System.nanoTime();
        assertThrows(SparqlUpdateTooLargeException.class, () -> scope(huge));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 1000, "took " + millis + " ms");
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void aSmallUpdateSkipsAdmissionEntirely() {
        signals.afterGc = 990L * FakeMemorySignals.MIB;
        scope("INSERT DATA { <http://x/A> <http://x/p> <http://x/B> }");
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void aLargeUpdateReservesAtLeastFifteenTimesItsTextAndReleasesAfterSuccess() {
        String text = insertOf(8000);
        long bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(bytes * SparqlUpdateLimits.PARSE_HEAP_FACTOR >= HeavyWorkAdmission.MIN_ESTIMATE_BYTES);
        long[] seen = {-1};
        HeavyWorkAdmission spy = new HeavyWorkAdmission(ledger, new self.research.ontology.common.admission.Estimates()) {
            @Override
            public Hold admit(String userId, self.research.ontology.common.admission.WorkKind kind, long estimateBytes) {
                Hold hold = super.admit(userId, kind, estimateBytes);
                seen[0] = hold.bytes();
                return hold;
            }
        };
        String scoped = SparqlUpdateScoper.scope(text, G, List.of(), null, limits, spy, "u1");
        assertTrue(scoped.contains("GRAPH <" + G + ">"));
        assertTrue(seen[0] >= bytes * SparqlUpdateLimits.PARSE_HEAP_FACTOR, "reserved " + seen[0]);
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void admissionIsReleasedWhenTheLargeUpdateFailsToParse() {
        String broken = insertOf(8000).replace("INSERT DATA {", "INSERT DATA { <http://x/open ");
        assertThrows(SparqlQueryRejectedException.class, () -> scope(broken));
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void admissionIsReleasedWhenTheLargeUpdateIsRejectedByTheScoper() {
        String rejected = insertOf(8000) + " ; LOAD <http://evil/x>";
        SparqlQueryRejectedException e = assertThrows(SparqlQueryRejectedException.class, () -> scope(rejected));
        assertEquals(SparqlUpdateScoper.MSG_LOAD, e.getMessage());
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void whenTheServerIsShortOfMemoryTheLargeUpdateGetsABusyAnswerAndNothingIsHeld() {
        signals.afterGc = 990L * FakeMemorySignals.MIB;
        assertThrows(BusyException.class, () -> scope(insertOf(8000)));
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void twoHundredThousandQuadsParseWithinTheLimitsAndTheCostIsMeasured() {
        SparqlUpdateLimits big = new SparqlUpdateLimits(64L * 1024 * 1024, 0, 0);
        String text = insertOf(200_000);
        long bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        System.gc();
        long before = usedHeap();
        java.util.concurrent.atomic.AtomicLong peak = new java.util.concurrent.atomic.AtomicLong();
        Thread sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                peak.accumulateAndGet(usedHeap(), Math::max);
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();
        long start = System.nanoTime();
        String scoped = SparqlUpdateScoper.scope(text, G, List.of(), null, big);
        long millis = (System.nanoTime() - start) / 1_000_000;
        sampler.interrupt();
        long during = usedHeap() - before;
        long peakDelta = peak.get() - before;
        System.out.println("[UPDATE-PERF] 200000 quads text=" + bytes + " bytes parse+scope+serialize=" + millis
                + " ms heapDelta(approx, scoped text still held)=" + during / (1024 * 1024) + " MiB peakHeapDelta(sampled, includes garbage)=" + peakDelta / (1024 * 1024) + " MiB scopedChars=" + scoped.length());
        assertTrue(scoped.contains("GRAPH"));
        SparqlQueryRejectedException refused = assertThrows(SparqlQueryRejectedException.class,
                () -> SparqlUpdateScoper.scope(insertOf(200_001), G, List.of(), null, big));
        assertTrue(refused.getMessage().contains("more than 200000 statements"));
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
