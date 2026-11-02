package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.swrlapi.sqwrl.SQWRLResult;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqwrlLoadMeasureTest {

    private static final List<String> COLS = List.of("p", "age");

    private static long usedHeap() throws Exception {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(50);
        }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static SwrlEngineService.SqwrlRead read(int rows, int limit, long maxBytes, int countLimit) throws Exception {
        SQWRLResult result = Mockito.mock(SQWRLResult.class);
        AtomicInteger served = new AtomicInteger();
        Mockito.when(result.next()).thenAnswer(i -> served.incrementAndGet() <= rows);
        AtomicInteger n = new AtomicInteger();
        return SwrlEngineService.readSqwrlRows(result, COLS, limit, maxBytes, countLimit,
                v -> "http://example.org/ontology#Individual" + n.incrementAndGet());
    }

    @Test
    void aHundredThousandRowResultWithTheDefaultCapsKeepsOnlyTheCap() throws Exception {
        long t0 = System.nanoTime();
        SwrlEngineService.SqwrlRead first = read(100_000, 1000, 8L << 20, 100_000);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(1000, first.rows().size());
        assertEquals(100_000, first.total());
        assertTrue(first.exact());

        long before = usedHeap();
        SwrlEngineService.SqwrlRead kept = read(100_000, 1000, 8L << 20, 100_000);
        SqwrlResultStore store = new SqwrlResultStore(900, 5, 50, 64L << 20);
        SqwrlResultStore.Entry entry = store.put("p1", "u1", COLS, kept.rows(),
                new SqwrlResultStore.Meta("q", "x", 1, kept.total(), true, true, 1000, false)).get();
        long after = usedHeap();
        long t1 = System.nanoTime();
        SqwrlPager.Page page = SqwrlPager.page(entry, "individual99", "p", true, 0, 100, 8L << 20);
        long pageMicros = (System.nanoTime() - t1) / 1000;
        System.out.println("SQWRL-MEASURE 100k rows, cap 1000: read+count " + ms + " ms, retained heap approx "
                + Math.max(0, after - before) / 1024 + " KiB, store estimate " + entry.estimatedBytes() / 1024
                + " KiB, filter+sort page " + pageMicros + " us, hits " + page.filteredRows());
        assertTrue(ms < 10_000);
        assertEquals(page.filteredRows(), page.rows().size());
        assertTrue(page.filteredRows() > 0);
    }

    @Test
    void keepingAllHundredThousandRowsWouldCostFarMore() throws Exception {
        long before = usedHeap();
        long t0 = System.nanoTime();
        SwrlEngineService.SqwrlRead all = read(100_000, 100_000, Long.MAX_VALUE, 100_000);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long after = usedHeap();
        long estimate = SqwrlResultStore.estimateBytes(COLS, all.rows());
        System.out.println("SQWRL-MEASURE 100k rows, keeping all: read " + ms + " ms, retained heap approx "
                + Math.max(0, after - before) / (1024 * 1024) + " MiB, store estimate " + estimate / (1024 * 1024) + " MiB");
        assertEquals(100_000, all.rows().size());
        assertFalse(all.bytesCapped());
    }

    @Test
    void theByteCapStopsStoringAHugeResult() throws Exception {
        SwrlEngineService.SqwrlRead capped = read(100_000, 100_000, 8L << 20, 100_000);
        assertTrue(capped.bytesCapped());
        assertTrue(capped.rows().size() < 100_000);
        assertEquals(100_000, capped.total());
    }
}
