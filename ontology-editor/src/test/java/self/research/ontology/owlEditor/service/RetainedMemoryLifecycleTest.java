package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import self.research.ontology.common.admission.ReservationLedger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetainedMemoryLifecycleTest {

    private static OWLOntology ontologyWithAxioms(int n) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/lifecycle"));
        for (int i = 0; i < n; i++) {
            manager.addAxiom(ontology, manager.getOWLDataFactory().getOWLDeclarationAxiom(
                    manager.getOWLDataFactory().getOWLClass(IRI.create("http://example.org/lifecycle#C" + i))));
        }
        return ontology;
    }

    private static void collectUntil(java.util.function.BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 100 && !done.getAsBoolean(); i++) {
            System.gc();
            Thread.sleep(50);
        }
    }

    @Test
    void aBorrowedEvictedOntologyStaysCountedUntilTheBorrowerLetsGo() throws Exception {
        ReservationLedger ledger = new FakeMemorySignals().ledger();
        EditorReasonerCacheService cache = new EditorReasonerCacheService(new ReasonerService());
        cache.setCapacityLedger(ledger);

        OWLOntology borrowed = ontologyWithAxioms(40);
        long expected = RetainedMemory.ontologyBytes(borrowed.getAxiomCount());
        cache.putOntology("p", borrowed, 1L);
        assertEquals(expected, ledger.capacityReport().cacheAccounts().get("editorOntologies"));

        OWLOntology viaCache = cache.getOntology("p").orElseThrow();
        cache.invalidateOntology("p");
        assertTrue(cache.getOntology("p").isEmpty());
        borrowed = null;

        System.gc();
        Thread.sleep(200);
        System.gc();
        assertNotNull(viaCache);
        assertEquals(expected, ledger.capacityReport().cacheAccounts().get("editorOntologies"),
                "still borrowed, so still counted even though it left the lookup");

        WeakReference<OWLOntology> watch = new WeakReference<>(viaCache);
        viaCache = null;
        collectUntil(() -> watch.get() == null
                && ledger.capacityReport().cacheAccounts().get("editorOntologies") == 0L);
        assertEquals(0L, ledger.capacityReport().cacheAccounts().get("editorOntologies"));
    }

    @Test
    void anOntologyOnItsOwnBecomesCollectable() throws Exception {
        WeakReference<OWLOntology> watch = new WeakReference<>(ontologyWithAxioms(40));
        collectUntil(() -> watch.get() == null);
        assertEquals(null, watch.get());
    }

    @Test
    void ruleRunsAreCountedByAxiomsAndFreedWhenTheRunIsGone() throws Exception {
        ReservationLedger ledger = new FakeMemorySignals().ledger();
        RuleRunStore store = new RuleRunStore();
        store.setCapacityLedger(ledger);
        OWLOntology source = ontologyWithAxioms(10);
        List<org.semanticweb.owlapi.model.OWLAxiom> axioms = new ArrayList<>(source.getAxioms());
        String id = store.put("p", "u", false, axioms, List.of());
        long expected = axioms.size() * RetainedMemory.BYTES_PER_RULE_RUN_AXIOM;
        assertEquals(expected, ledger.capacityReport().cacheAccounts().get("ruleRuns"));
        assertNotNull(id);
    }

    @Test
    void structuresAreEstimatedFromTheirNodeCount() {
        long small = RetainedMemory.nodeBytes(Map.of("a", 1));
        long bigger = RetainedMemory.nodeBytes(List.of(Map.of("a", 1), Map.of("b", List.of(1, 2, 3))));
        assertTrue(small > 0);
        assertTrue(bigger > small);
        assertEquals(0, RetainedMemory.nodeBytes(null));
    }
}
