package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparqlRunLoadMeasureTest {

    private static final String G = "http://example.org/g";
    private static final String P = "http://example.org/p";
    private static final int ROWS = 100_000;
    private static final String QUERY = "SELECT ?s ?o WHERE { ?s <" + P + "> ?o }";

    private static SailRepository repo;

    @BeforeAll
    static void load() {
        ValueFactory vf = SimpleValueFactory.getInstance();
        repo = new SailRepository(new MemoryStore());
        repo.init();
        try (RepositoryConnection c = repo.getConnection()) {
            c.begin();
            for (int i = 0; i < ROWS; i++) {
                c.add(vf.createIRI("http://example.org/individual/" + i), vf.createIRI(P),
                        vf.createLiteral("value number " + (i % 977) + " for item " + i), vf.createIRI(G));
            }
            c.commit();
        }
    }

    @AfterAll
    static void unload() {
        repo.shutDown();
    }

    private static long usedHeap() throws Exception {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(50);
        }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static SparqlUserQueryResult.Select run(SparqlResultLimits retain) {
        try (RepositoryConnection c = repo.getConnection()) {
            SparqlQueryGuard.Checked checked = SparqlQueryGuard.check(QUERY, Set.of(G));
            return (SparqlUserQueryResult.Select) SparqlUserQueryRunner.run(c, checked, List.of(G), 1000,
                    SparqlResultLimits.defaults(), retain);
        }
    }

    private void measure(String label, int runRows, long runBytes) throws Exception {
        SparqlRunStore store = new SparqlRunStore(Clock.systemUTC(), Duration.ofMinutes(15), 5, 50, 64L << 20,
                runRows, runBytes);
        run(store.retention());
        long t0 = System.nanoTime();
        SparqlUserQueryResult.Select select = run(store.retention());
        long runMs = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(ROWS, select.totalRows());
        assertTrue(select.rows().size() <= runRows);
        long before = usedHeap();
        long t1 = System.nanoTime();
        SparqlRunStore.Entry entry = store.put("p", "u", select.vars(), select.rows(), select.totalRows(),
                select.truncated(), runMs);
        long putMs = (System.nanoTime() - t1) / 1_000_000;
        select = null;
        long after = usedHeap();
        long t2 = System.nanoTime();
        SparqlRunPager.Page filtered = SparqlRunPager.page(entry, "item 99", null, false, 0, 100, 8L << 20);
        long filterMs = (System.nanoTime() - t2) / 1_000_000;
        long t3 = System.nanoTime();
        SparqlRunPager.Page sorted = SparqlRunPager.page(entry, null, "o", true, 0, 100, 8L << 20);
        long sortMs = (System.nanoTime() - t3) / 1_000_000;
        long t4 = System.nanoTime();
        SparqlRunPager.page(entry, null, "o", true, 100, 100, 8L << 20);
        long sortAgainMs = (System.nanoTime() - t4) / 1_000_000;
        System.out.println("SPARQL-MEASURE " + label + ": query+count " + runMs + " ms, stored " + entry.rows().length
                + " of " + entry.meta().totalRows() + " rows, put " + putMs + " ms, store estimate "
                + entry.estimatedBytes() / 1024 + " KiB, retained heap approx " + Math.max(0, after - before) / 1024
                + " KiB (after dropping the query rows), filter " + filterMs + " ms (" + filtered.filteredRows()
                + " hits), first sort " + sortMs + " ms, cached sort page " + sortAgainMs + " ms");
        assertFalse(sorted.rows().isEmpty());
        assertTrue(entry.estimatedBytes() <= Math.max(runBytes, 1) + 1024);
    }

    @Test
    void theRunnerRetainsOnlyTheRetentionRowsButCountsEveryRow() {
        SparqlUserQueryResult.Select select = run(new SparqlResultLimits(50, 16L << 20));
        assertEquals(50, select.rows().size());
        assertEquals(ROWS, select.totalRows());
        assertFalse(select.truncated());
    }

    @Test
    void theRunnerKeepsEveryRowWhenNoRetentionIsGiven() {
        SparqlUserQueryResult.Select select = run(null);
        assertEquals(ROWS, select.rows().size());
        assertEquals(ROWS, select.totalRows());
    }

    @Test
    void aHundredThousandRowResultWithTheDefaultCapsKeepsOnlyTheCap() throws Exception {
        measure("100k rows, default caps (10000 rows, 16 MiB)", 10_000, 16L << 20);
    }

    @Test
    void aHundredThousandRowResultWithAllRowsRetained() throws Exception {
        measure("100k rows, caps raised to 100000 rows and 64 MiB", 100_000, 64L << 20);
    }
}
