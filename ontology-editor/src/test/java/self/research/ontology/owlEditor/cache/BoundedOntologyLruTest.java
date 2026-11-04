package self.research.ontology.owlEditor.cache;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedOntologyLruTest {

    static OWLOntology ontology() throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology();
        m.addAxiom(o, m.getOWLDataFactory().getOWLDeclarationAxiom(
                m.getOWLDataFactory().getOWLClass(IRI.create("http://e/A"))));
        return o;
    }

    @Test
    void neverHoldsMoreThanTheBoundAndEvictsLeastRecentlyUsed() throws Exception {
        BoundedOntologyLru lru = new BoundedOntologyLru(2, "lruTestA");
        lru.put("a", ontology());
        lru.put("b", ontology());
        lru.get("a");
        lru.put("c", ontology());
        assertEquals(2, lru.size());
        assertNull(lru.get("b"));
        assertTrue(lru.get("a") != null && lru.get("c") != null);
    }

    @Test
    void accountReturnsToZeroAfterRemoveAndClear() throws Exception {
        BoundedOntologyLru lru = new BoundedOntologyLru(2, "lruTestB");
        for (int i = 0; i < 6; i++) {
            lru.put("p" + i, ontology());
        }
        assertTrue(lru.account().bytes() > 0);
        lru.remove("p5");
        lru.clear();
        assertEquals(0, lru.size());
        assertEquals(0, lru.account().bytes());
        assertEquals(0, lru.account().underflowCount());
    }

    @Test
    void concurrentLoadersForOneKeyLoadOnceAndManyKeysStayBounded() throws Exception {
        BoundedOntologyLru lru = new BoundedOntologyLru(3, "lruTestC");
        AtomicInteger loads = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<OWLOntology>> same = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            same.add(pool.submit(() -> lru.getOrLoad("shared", () -> {
                loads.incrementAndGet();
                Thread.sleep(50);
                return ontology();
            })));
        }
        OWLOntology first = same.get(0).get();
        for (Future<OWLOntology> f : same) {
            assertSame(first, f.get());
        }
        assertEquals(1, loads.get());
        List<Future<?>> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int n = i;
            many.add(pool.submit(() -> {
                lru.getOrLoad("k" + (n % 20), BoundedOntologyLruTest::ontology);
                assertTrue(lru.size() <= 3);
                return null;
            }));
        }
        for (Future<?> f : many) {
            f.get();
        }
        pool.shutdown();
        assertEquals(3, lru.size());
        lru.clear();
        assertEquals(0, lru.account().bytes());
    }
}
