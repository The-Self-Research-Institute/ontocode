package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.Models;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.HistoryChangeRepository;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.delta.DeltaSparql;
import self.research.ontology.owlEditor.service.delta.ExactDelta;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DesktopInMemoryRoundTripTest {

    private static final String NS = "http://ex/";
    private static final String PREFIXES = "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n"
            + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\nPREFIX owl: <http://www.w3.org/2002/07/owl#>\n";

    private ProjectOntologyCache cache;
    private InMemorySparqlOntologyMutator mutator;
    private HistoryChangeRepository history;
    private ExactChangeRecorder recorder;

    @BeforeEach
    void setUp() throws Exception {
        OWLOntologyManager manager = OWLManager.createConcurrentOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://ex/onto"));
        OWLClass a = factory.getOWLClass(IRI.create(NS + "A"));
        OWLClass b = factory.getOWLClass(IRI.create(NS + "B"));
        OWLClass c = factory.getOWLClass(IRI.create(NS + "C"));
        OWLObjectProperty p = factory.getOWLObjectProperty(IRI.create(NS + "p"));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(a));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(b));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(c));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(p));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(c, b));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(a, factory.getOWLObjectSomeValuesFrom(p, b)));
        cache = new ProjectOntologyCache(true);
        cache.put("p", ontology, null, manager, true);
        mutator = new InMemorySparqlOntologyMutator(cache);
        DesktopOwlApiMutationService desktop = mock(DesktopOwlApiMutationService.class);
        when(desktop.isAuthoritative("p")).thenReturn(true);
        history = mock(HistoryChangeRepository.class);
        recorder = new ExactChangeRecorder(mock(SparqlDatasetService.class), history, mock(OntologyHistoryService.class));
        ReflectionTestUtils.setField(recorder, "desktopOwlApi", desktop);
        ReflectionTestUtils.setField(recorder, "ontologyCache", cache);
    }

    private Model snapshot() {
        return OwlApiModelSnapshot.of(cache, "p").orElseThrow();
    }

    private ExactDelta record(String iri, String type, String sparql) {
        return record(iri, type, () -> assertTrue(mutator.tryApply("p", PREFIXES + sparql)));
    }

    private ExactDelta record(String iri, String type, Runnable apply) {
        HistoryChange entry = new HistoryChange();
        entry.setId("e1");
        entry.setEntityIRI(iri);
        entry.setChangeSetId("cs-1");
        entry.setTimestamp(java.time.LocalDateTime.of(2026, 10, 14, 10, 0));
        when(history.findByProjectIdAndChangeSetIdOrderByTimestampDesc("p", "cs-1")).thenReturn(List.of(entry));
        recorder.recordAround("p", false, "u",
                List.of(new MutationOp(type, iri, null, null, null, null, null, null, null, null, null, null, null, null, null)),
                apply, () -> "cs-1");
        assertTrue(entry.carriesExactDelta(), "the in-memory change was not recorded exactly");
        return ExactDelta.of(entry.getExactDeleted(), entry.getExactInserted());
    }

    private void roundTrip(ExactDelta delta, Model before, Model changed) {
        assertTrue(mutator.tryApply("p", PREFIXES + DeltaSparql.reverse(delta)), "undo could not be applied");
        assertTrue(Models.isomorphic(before, snapshot()), "undo did not restore the in-memory ontology");
        assertTrue(mutator.tryApply("p", PREFIXES + DeltaSparql.forward(delta)), "redo could not be applied");
        assertTrue(Models.isomorphic(changed, snapshot()), "redo did not repeat the change");
        assertTrue(mutator.tryApply("p", PREFIXES + DeltaSparql.reverse(delta)));
        assertTrue(Models.isomorphic(before, snapshot()), "second undo did not restore the in-memory ontology");
    }

    @Test
    void addingARestrictionIsUndoneAndRedoneInTheInMemoryOntology() {
        Model before = snapshot();

        ExactDelta delta = record(NS + "C", "addObjectRestriction",
                "INSERT DATA { <" + NS + "C> rdfs:subClassOf [ a owl:Restriction ; owl:onProperty <" + NS + "p> ; "
                        + "owl:allValuesFrom <" + NS + "A> ] . }");

        Model changed = snapshot();
        assertFalse(Models.isomorphic(before, changed));
        roundTrip(delta, before, changed);
    }

    @Test
    void deletingAReferencedClassIsUndoneInTheInMemoryOntology() {
        Model before = snapshot();

        ExactDelta delta = record(NS + "B", "deleteClass", () -> {
            ProjectOntologyCache.CachedOntology live = cache.get("p").orElseThrow();
            OWLClass b = live.manager().getOWLDataFactory().getOWLClass(IRI.create(NS + "B"));
            live.manager().removeAxioms(live.ontology(),
                    live.ontology().axioms().filter(axiom -> axiom.containsEntityInSignature(b)).collect(java.util.stream.Collectors.toSet()));
        });

        Model changed = snapshot();
        assertTrue(changed.size() < before.size());
        assertTrue(delta.deleted().size() > 3);
        roundTrip(delta, before, changed);
    }
}
