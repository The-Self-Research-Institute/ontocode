package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuralReasonerListenerTest {

    static int changeListeners(OWLOntologyManager manager) throws Exception {
        Field f = manager.getClass().getDeclaredField("listenerMap");
        f.setAccessible(true);
        return ((Map<?, ?>) f.get(manager)).size();
    }

    @Test
    void nonBufferingReasonerLeavesAListenerOnTheManagerEvenAfterDispose() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ont = manager.createOntology();
        int before = changeListeners(manager);
        for (int i = 0; i < 50; i++) {
            new StructuralReasonerFactory().createNonBufferingReasoner(ont);
        }
        int leaked = changeListeners(manager);
        System.out.println("[LISTENER-EVIDENCE] before=" + before + " after50NotDisposed=" + leaked);
        assertEquals(before + 50, leaked);
        OWLReasoner r = new StructuralReasonerFactory().createNonBufferingReasoner(ont);
        r.dispose();
        System.out.println("[LISTENER-EVIDENCE] afterOneCreatedAndDisposed=" + changeListeners(manager));
        assertEquals(leaked + 1, changeListeners(manager));
    }

    @Test
    void cachedEntryReusesOneStructuralReasonerForAnyNumberOfRequests() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ont = manager.createOntology();
        ProjectOntologyCache cache = new ProjectOntologyCache(true);
        cache.put("p", ont, null, manager, true);
        int before = changeListeners(manager);
        for (int i = 0; i < 100; i++) {
            cache.get("p").orElseThrow().structuralReasoner();
        }
        assertEquals(before + 1, changeListeners(manager));
        assertSame(cache.get("p").orElseThrow().structuralReasoner(), cache.get("p").orElseThrow().structuralReasoner());
    }

    @Test
    void ontologyCacheAccountGrowsOnPutAndReturnsToZeroOnEvict() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ont = manager.createOntology();
        manager.addAxiom(ont, manager.getOWLDataFactory().getOWLDeclarationAxiom(
                manager.getOWLDataFactory().getOWLClass(IRI.create("http://e/A"))));
        ProjectOntologyCache cache = new ProjectOntologyCache(true);
        assertEquals(0, cache.account().bytes());
        cache.put("p", ont, null, manager, true);
        assertTrue(cache.account().bytes() > 0);
        OWLOntologyManager manager2 = OWLManager.createOWLOntologyManager();
        cache.put("q", manager2.createOntology(), null, manager2, true);
        assertEquals(1, cache.size());
        cache.evict("q");
        assertEquals(0, cache.account().bytes());
        assertEquals(0, cache.account().underflowCount());
    }
}
