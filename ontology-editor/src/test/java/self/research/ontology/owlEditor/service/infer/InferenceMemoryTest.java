package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.SparqlQueryScope;
import self.research.ontology.owlEditor.service.SparqlUserQueryResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceMemoryTest {

    static final ValueFactory VF = SimpleValueFactory.getInstance();

    static List<Statement> classTree(SparqlQueryScope scope, int classes) {
        IRI g = VF.createIRI(scope.graphs().get(0));
        List<Statement> out = new ArrayList<>(classes * 4);
        for (int i = 0; i < classes; i++) {
            IRI c = VF.createIRI("http://e/C" + i);
            out.add(VF.createStatement(c, RDF.TYPE, OWL.CLASS, g));
            out.add(VF.createStatement(c, RDFS.LABEL, VF.createLiteral("Class number " + i), g));
            out.add(VF.createStatement(c, RDFS.COMMENT, VF.createLiteral("A generated class used to measure memory " + i), g));
            if (i > 0) {
                out.add(VF.createStatement(c, RDFS.SUBCLASSOF, VF.createIRI("http://e/C" + ((i - 1) / 10)), g));
            }
        }
        out.add(VF.createStatement(VF.createIRI("http://e/ind"), RDF.TYPE, VF.createIRI("http://e/C" + (classes - 1)), g));
        return out;
    }

    static List<Statement> mixed(SparqlQueryScope scope, int classes, int individuals, int annotations) {
        IRI g = VF.createIRI(scope.graphs().get(0));
        List<Statement> out = classTree(scope, classes);
        for (int c = 0; c < classes; c++) {
            for (int a = 0; a < annotations; a++) {
                out.add(VF.createStatement(VF.createIRI("http://e/C" + c), RDFS.SEEALSO, VF.createLiteral("note " + a + " of class " + c), g));
            }
        }
        for (int i = 0; i < individuals; i++) {
            IRI ind = VF.createIRI("http://e/i" + i);
            out.add(VF.createStatement(ind, RDF.TYPE, VF.createIRI("http://e/C" + (classes / 2 + i % (classes / 2))), g));
        }
        return out;
    }

    static final class PeakSampler implements AutoCloseable {
        final AtomicLong peak = new AtomicLong();
        final Thread thread;
        volatile boolean running = true;

        PeakSampler() {
            thread = new Thread(() -> {
                Runtime rt = Runtime.getRuntime();
                while (running) {
                    peak.accumulateAndGet(rt.totalMemory() - rt.freeMemory(), Math::max);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void close() {
            running = false;
        }
    }

    static long usedAfterGc() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    static void measure(String label, SnapshotStoreFactory stores, int classes) {
        InferenceTestKit.class.getName();
        SparqlQueryScope scope = InferenceFixtures.publicScope();
        InferenceFixtures.MutableSource source = new InferenceFixtures.MutableSource();
        source.set(scope, mixed(scope, 1_000, 100, 90));
        InferenceService service = new InferenceService(source, null,
                new InferenceSnapshotCache(2, 1_800_000L, 600_000L, System::currentTimeMillis), 10, 100_000, 200_000,
                stores, HeapBudget.runtime(), new LocalInferenceAdmission(5000L, HeapBudget.runtime()));
        long before = usedAfterGc();
        long start = System.nanoTime();
        long peak;
        try (PeakSampler sampler = new PeakSampler()) {
            InferenceService.Answer answer = service.query(scope, null,
                    "PREFIX : <http://e/> ASK { :ind a :C0 }", true, 10);
            assertTrue(((SparqlUserQueryResult.Ask) answer.result()).value());
            peak = sampler.peak.get();
            long total = (Long) answer.inferred().get("assertedTripleCount") + (Long) answer.inferred().get("inferredTripleCount");
            long ms = (System.nanoTime() - start) / 1_000_000;
            long resident = usedAfterGc() - before;
            System.out.println("[MEM] " + label + " maxHeapMB=" + Runtime.getRuntime().maxMemory() / 1048576
                    + " asserted=" + answer.inferred().get("assertedTripleCount") + " inferred=" + answer.inferred().get("inferredTripleCount")
                    + " totalMs=" + ms + " steps=" + service.lastStepMillis()
                    + " peakHeapMB=" + peak / 1048576 + " residentMB=" + Math.max(0, resident) / 1048576
                    + " bytesPerTriple=" + Math.max(0, resident) / Math.max(1, total));
        }
        service.cache().clear();
    }

    @Test
    void aSnapshotAtAboutOneHundredThousandTriplesSucceedsInMemoryAndOnDisk() {
        measure("memoryStore", new TieredSnapshotStores(Long.MAX_VALUE), 0);
        measure("nativeStore", new TieredSnapshotStores(0), 0);
    }

    @Test
    void anOversizeRequestIsRefusedWithAConflictNotAnOutOfMemoryError() {
        SparqlQueryScope scope = InferenceFixtures.publicScope();
        InferenceFixtures.MutableSource source = new InferenceFixtures.MutableSource();
        source.set(scope, classTree(scope, 30_000));
        InferenceService service = new InferenceService(source, null, InferenceSnapshotCache.standard(), 10, 100_000, 100_000);
        InferenceException e = assertThrows(InferenceException.class,
                () -> service.query(scope, null, "ASK { ?s ?p ?o }", true, 10));
        assertEquals(InferenceException.TOO_LARGE, e.reason());
        assertTrue((Boolean) service.status(scope, null).get("tooLarge"));
    }

    @Test
    void theRepositorySourceStopsReadingAtTheLimit() {
        SparqlQueryScope scope = InferenceFixtures.publicScope();
        Repository repo = new SailRepository(new MemoryStore());
        repo.init();
        try (RepositoryConnection c = repo.getConnection()) {
            c.add(classTree(scope, 100));
        }
        SparqlDatasetService dataset = Mockito.mock(SparqlDatasetService.class);
        Mockito.when(dataset.getRepository(InferenceFixtures.PROJECT)).thenReturn(repo);
        RepositoryInferenceSource source = new RepositoryInferenceSource(dataset);
        assertEquals(400, source.read(scope, 1000).size());
        InferenceException e = assertThrows(InferenceException.class, () -> source.read(scope, 50));
        assertEquals(InferenceException.TOO_LARGE, e.reason());
    }

    @Test
    void aSecondMaterializationWaitsBrieflyThenGetsBusy() throws Exception {
        InferenceTestKit kit = new InferenceTestKit();
        SparqlQueryScope scope = InferenceFixtures.publicScope();
        SparqlQueryScope other = InferenceFixtures.draftScope("u9");
        InferenceFixtures.MutableSource source = kit.source;
        source.set(scope, InferenceFixtures.turtle(InferenceEqualityTest.BASE, scope.graphs().get(0)));
        source.set(other, InferenceFixtures.turtle(InferenceEqualityTest.BASE, other.graphs().get(0)));
        InferenceService service = new InferenceService(source, null, InferenceSnapshotCache.standard(), 10, 100_000, 200_000,
                new TieredSnapshotStores(), HeapBudget.runtime(), new LocalInferenceAdmission(200L, HeapBudget.runtime()));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        source.beforeRead = () -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        };
        Thread first = new Thread(() -> service.query(scope, null, "ASK { ?s ?p ?o }", true, 10));
        first.start();
        assertTrue(inside.await(10, TimeUnit.SECONDS));
        assertTrue((Boolean) service.status(scope, null).get("busy"));
        InferenceException busy = assertThrows(InferenceException.class, () -> service.query(other, "u9", "ASK { ?s ?p ?o }", true, 10));
        assertEquals(InferenceException.BUSY, busy.reason());
        release.countDown();
        first.join(30_000);
    }

    @Test
    void lowFreeHeapRefusesANewMaterializationAndDropsCachedSnapshots() {
        InferenceTestKit kit = new InferenceTestKit();
        SparqlQueryScope scope = InferenceFixtures.publicScope();
        kit.source.set(scope, InferenceFixtures.turtle(InferenceEqualityTest.BASE, scope.graphs().get(0)));
        boolean[] low = {false};
        InferenceSnapshotCache cache = InferenceSnapshotCache.standard();
        InferenceService service = new InferenceService(kit.source, null, cache, 10, 100_000, 200_000,
                new TieredSnapshotStores(), new HeapBudget(() -> low[0]), new LocalInferenceAdmission(200L, new HeapBudget(() -> low[0])));
        service.query(scope, null, "ASK { ?s ?p ?o }", true, 10);
        assertEquals(1, cache.size());
        kit.source.bump(scope);
        low[0] = true;
        assertTrue((Boolean) service.status(scope, null).get("busy"));
        InferenceException e = assertThrows(InferenceException.class, () -> service.query(scope, null, "ASK { ?s ?p ?o }", true, 10));
        assertEquals(InferenceException.BUSY, e.reason());
        assertEquals(0, cache.size());
        low[0] = false;
        assertFalse((Boolean) service.status(scope, null).get("busy"));
    }

    @Test
    void theStatusReportsTheLimits() {
        InferenceTestKit kit = new InferenceTestKit();
        SparqlQueryScope scope = InferenceFixtures.publicScope();
        kit.source.set(scope, InferenceFixtures.turtle(InferenceEqualityTest.BASE, scope.graphs().get(0)));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> limits = (java.util.Map<String, Object>) kit.service.status(scope, null).get("limits");
        assertEquals(200_000, limits.get("maxAssertedTriples"));
        assertEquals(100_000, limits.get("maxEqualityTriples"));
        assertEquals(10, limits.get("maxGroupSize"));
    }

    @Test
    void bothStoresPassTheAcceptanceFixture() {
        for (long threshold : new long[] {Long.MAX_VALUE, 0L}) {
            SparqlQueryScope scope = InferenceFixtures.publicScope();
            InferenceFixtures.MutableSource source = new InferenceFixtures.MutableSource();
            source.set(scope, InferenceFixtures.turtle(InferenceEqualityTest.BASE, scope.graphs().get(0)));
            InferenceService service = new InferenceService(source, null, InferenceSnapshotCache.standard(), 10, 100_000, 200_000,
                    new TieredSnapshotStores(threshold), HeapBudget.runtime(), new LocalInferenceAdmission(1000L, HeapBudget.runtime()));
            String p = InferenceTestKit.P;
            for (String q : List.of(":JohnSmith :worksAt :HospitalA", ":Alice :knows :JohnSmith", ":JohnSmith a :Person")) {
                assertTrue(((SparqlUserQueryResult.Ask) service.query(scope, null, p + "ASK { " + q + " }", true, 10).result()).value(), q);
            }
            service.cache().clear();
        }
    }
}
