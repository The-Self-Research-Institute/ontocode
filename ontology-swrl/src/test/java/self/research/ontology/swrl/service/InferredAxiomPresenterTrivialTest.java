package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InferredAxiomPresenterTrivialTest {

    private static final OWLDataFactory F = OWLManager.getOWLDataFactory();
    private static final OWLClass FEVER = F.getOWLClass(IRI.create("http://example.org/clinic#Fever"));
    private static final OWLClass SENIOR = F.getOWLClass(IRI.create("http://example.org/clinic#Senior"));
    private static final OWLClass RISK = F.getOWLClass(IRI.create("http://example.org/clinic#HighRisk"));

    @Test
    void emptyTopBottomAndSelfSubclassesAreDroppedAndCounted() {
        Set<OWLAxiom> inferred = new HashSet<>();
        inferred.add(F.getOWLSubClassOfAxiom(FEVER, F.getOWLThing()));
        inferred.add(F.getOWLSubClassOfAxiom(F.getOWLObjectIntersectionOf(FEVER, SENIOR), F.getOWLThing()));
        inferred.add(F.getOWLSubClassOfAxiom(F.getOWLNothing(), FEVER));
        inferred.add(F.getOWLSubClassOfAxiom(F.getOWLNothing(), F.getOWLObjectIntersectionOf(FEVER, SENIOR)));
        inferred.add(F.getOWLSubClassOfAxiom(FEVER, FEVER));
        inferred.add(F.getOWLSubClassOfAxiom(FEVER, F.getOWLObjectIntersectionOf(FEVER, F.getOWLThing())));
        inferred.add(F.getOWLEquivalentClassesAxiom(FEVER, F.getOWLObjectIntersectionOf(FEVER, F.getOWLThing())));
        inferred.add(F.getOWLSubClassOfAxiom(FEVER, RISK));

        InferredAxiomPresenter.Presented presented = InferredAxiomPresenter.presentNew(inferred, Set.of(), 100);

        assertEquals(1, presented.ordered().size(), presented.ordered().toString());
        assertEquals(F.getOWLSubClassOfAxiom(FEVER, RISK), presented.ordered().get(0));
        assertEquals(7, presented.omittedTrivial());
    }

    @Test
    void aRealIntersectionDefinitionIsKept() {
        Set<OWLAxiom> inferred = Set.of(F.getOWLEquivalentClassesAxiom(RISK, F.getOWLObjectIntersectionOf(FEVER, SENIOR)));

        InferredAxiomPresenter.Presented presented = InferredAxiomPresenter.presentNew(inferred, Set.of(), 100);

        assertEquals(0, presented.omittedTrivial());
        assertEquals(1, presented.ordered().size());
    }
}
