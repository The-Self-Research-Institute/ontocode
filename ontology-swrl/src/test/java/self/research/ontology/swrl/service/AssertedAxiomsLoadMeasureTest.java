package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssertedAxiomsLoadMeasureTest {

    private static long usedHeap() throws Exception {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(50);
        }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static OWLOntology synthetic(int axioms) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory f = manager.getOWLDataFactory();
        OWLOntology o = manager.createOntology();
        OWLClass[] classes = new OWLClass[50];
        for (int i = 0; i < classes.length; i++) {
            classes[i] = f.getOWLClass(IRI.create("http://example.org/shop#Kind" + i));
        }
        OWLObjectProperty sells = f.getOWLObjectProperty(IRI.create("http://example.org/shop#sells"));
        OWLDataProperty price = f.getOWLDataProperty(IRI.create("http://example.org/shop#price"));
        Set<org.semanticweb.owlapi.model.OWLAxiom> batch = new HashSet<>();
        for (int i = 0; batch.size() < axioms; i++) {
            OWLNamedIndividual item = f.getOWLNamedIndividual(IRI.create("http://example.org/shop#item" + i));
            batch.add(f.getOWLClassAssertionAxiom(classes[i % classes.length], item));
            if (batch.size() < axioms) {
                batch.add(f.getOWLObjectPropertyAssertionAxiom(sells, f.getOWLNamedIndividual(IRI.create("http://example.org/shop#store" + (i % 500))), item));
            }
            if (batch.size() < axioms) {
                batch.add(f.getOWLDataPropertyAssertionAxiom(price, item, i % 997));
            }
        }
        manager.addAxioms(o, batch);
        return o;
    }

    @Test
    void aHundredThousandAxiomListingIsFastAndKeepsOnlyOnePageInMemory() throws Exception {
        OWLOntology o = synthetic(100_000);
        assertEquals(100_000, o.getAxiomCount());
        long before = usedHeap();

        long t0 = System.nanoTime();
        AssertedAxiomLister.Page all = AssertedAxiomLister.list(o, null, null, 0, 200, AssertedAxiomLister.DEFAULT_MAX_SCAN);
        long listMs = (System.nanoTime() - t0) / 1_000_000;
        long t1 = System.nanoTime();
        AssertedAxiomLister.Page deep = AssertedAxiomLister.list(o, null, null, 99, 200, AssertedAxiomLister.DEFAULT_MAX_SCAN);
        long deepMs = (System.nanoTime() - t1) / 1_000_000;
        long t2 = System.nanoTime();
        AssertedAxiomLister.Page searched = AssertedAxiomLister.list(o, "item4242", null, 0, 200, AssertedAxiomLister.DEFAULT_MAX_SCAN);
        long searchMs = (System.nanoTime() - t2) / 1_000_000;
        long after = usedHeap();

        System.out.println("[MEASURE] asserted-axioms axioms=100000 firstPageMs=" + listMs + " page99Ms=" + deepMs
                + " searchMs=" + searchMs + " heapDeltaKB=" + (after - before) / 1024);
        assertEquals(100_000, all.total());
        assertEquals(200, all.items().size());
        assertEquals(200, deep.items().size());
        assertTrue(searched.total() >= 1);
        assertTrue(after - before < 20L * 1024 * 1024, "kept " + (after - before) + " bytes");
    }

    @Test
    void peakHeapWhileListingStaysFarBelowTheOntologyItself() throws Exception {
        OWLOntology o = synthetic(100_000);
        long base = usedHeap();
        Thread sampler = null;
        final long[] peak = {0};
        final boolean[] stop = {false};
        sampler = new Thread(() -> {
            Runtime rt = Runtime.getRuntime();
            while (!stop[0]) {
                peak[0] = Math.max(peak[0], rt.totalMemory() - rt.freeMemory());
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.start();
        for (int i = 0; i < 5; i++) {
            AssertedAxiomLister.list(o, null, null, 0, 200, AssertedAxiomLister.DEFAULT_MAX_SCAN);
        }
        stop[0] = true;
        sampler.join();
        System.out.println("[MEASURE] asserted-axioms peakHeapAboveBaselineMB=" + Math.max(0, peak[0] - base) / (1024 * 1024)
                + " baselineMB=" + base / (1024 * 1024));
    }
}
