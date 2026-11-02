package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.HermiT.Configuration;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HermitInterruptTest {

    static OWLOntology pigeonhole(int pigeons, int holes) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/pigeons"));
        OWLClass[][] in = new OWLClass[pigeons][holes];
        for (int p = 0; p < pigeons; p++) {
            for (int h = 0; h < holes; h++) {
                in[p][h] = df.getOWLClass(IRI.create("http://example.org/P" + p + "H" + h));
            }
        }
        OWLClassExpression top = df.getOWLThing();
        for (int p = 0; p < pigeons; p++) {
            manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(top, df.getOWLObjectUnionOf(in[p])));
        }
        for (int h = 0; h < holes; h++) {
            for (int p = 0; p < pigeons; p++) {
                for (int q = p + 1; q < pigeons; q++) {
                    manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(top, df.getOWLObjectUnionOf(
                            df.getOWLObjectComplementOf(in[p][h]), df.getOWLObjectComplementOf(in[q][h]))));
                }
            }
        }
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(top,
                df.getOWLNamedIndividual(IRI.create("http://example.org/a"))));
        return ontology;
    }

    private static OWLReasoner hermit(OWLOntology ontology) {
        Configuration config = new Configuration();
        config.ignoreUnsupportedDatatypes = true;
        config.individualTaskTimeout = 10 * 60_000L;
        return new ReasonerFactory().createReasoner(ontology, config);
    }

    @Test
    void interruptingHermitFromAnotherThreadStopsALongRunningCheck() throws Exception {
        OWLReasoner reasoner = hermit(pigeonhole(12, 11));
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                reasoner.isConsistent();
            } catch (Throwable t) {
                outcome.set(t);
            }
        }, "hermit-interrupt-test");
        worker.setDaemon(true);
        worker.start();

        Thread.sleep(1_500);
        boolean stillRunning = worker.isAlive();
        reasoner.interrupt();
        worker.join(15_000);

        assertTrue(stillRunning);
        assertFalse(worker.isAlive());
        assertInstanceOf(ReasonerInterruptedException.class, outcome.get());
        reasoner.dispose();
    }

    @Test
    void aPlainThreadInterruptAloneDoesNotStopHermit() throws Exception {
        OWLReasoner reasoner = hermit(pigeonhole(12, 11));
        Thread worker = new Thread(() -> {
            try {
                reasoner.isConsistent();
            } catch (Throwable ignored) {
            }
        }, "hermit-plain-interrupt-test");
        worker.setDaemon(true);
        worker.start();

        Thread.sleep(1_500);
        worker.interrupt();
        worker.join(3_000);
        boolean survived = worker.isAlive();
        reasoner.interrupt();
        worker.join(15_000);

        assertTrue(survived);
        assertFalse(worker.isAlive());
        reasoner.dispose();
    }
}
