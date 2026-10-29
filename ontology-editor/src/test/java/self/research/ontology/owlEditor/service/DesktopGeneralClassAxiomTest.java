package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.Models;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.service.delta.DeltaSparql;
import self.research.ontology.owlEditor.service.delta.ExactDelta;
import self.research.ontology.owlEditor.util.OwlAxiomSparqlWriter;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;

class DesktopGeneralClassAxiomTest {

    private static final String NS = "http://ex/";
    private static final String PREFIXES = "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n"
            + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\nPREFIX owl: <http://www.w3.org/2002/07/owl#>\n";

    private ProjectOntologyCache cache;
    private InMemorySparqlOntologyMutator mutator;
    private ManchesterExpressionService service;
    private OWLDataFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        OWLOntologyManager manager = OWLManager.createConcurrentOWLOntologyManager();
        factory = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://ex/onto"));
        for (String name : List.of("Seed", "Water", "Bird", "Pet")) {
            manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(clazz(name)));
        }
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(property("eats")));
        manager.addAxiom(ontology, factory.getOWLDeclarationAxiom(property("livesIn")));
        cache = new ProjectOntologyCache(true);
        cache.put("p", ontology, null, manager, true);
        mutator = new InMemorySparqlOntologyMutator(cache);

        OntologyMutationService mutations = mock(OntologyMutationService.class);
        Mockito.doAnswer(inv -> {
            assertTrue(mutator.tryApply("p", inv.getArgument(1)), "the in-memory ontology refused the update");
            return null;
        }).when(mutations).applyRawUpdateWithHistory(eq("p"), anyString(), anyBoolean(), any(), any());
        service = new ManchesterExpressionService(mock(StorageManager.class), mock(SparqlDatasetService.class), mutations);
        ReflectionTestUtils.setField(service, "ontologyCache", cache);
    }

    private OWLClass clazz(String name) {
        return factory.getOWLClass(IRI.create(NS + name));
    }

    private OWLObjectProperty property(String name) {
        return factory.getOWLObjectProperty(IRI.create(NS + name));
    }

    private OWLOntology live() {
        return cache.get("p").orElseThrow().ontology();
    }

    private List<GeneralClassAxioms.Entry> listed() {
        return GeneralClassAxioms.list(live());
    }

    private Model snapshot() {
        return OwlApiModelSnapshot.of(cache, "p").orElseThrow();
    }

    @Test
    void aGeneralClassAxiomCanBeAddedAndIsListed() throws Exception {
        service.addGeneralClassAxiom("p", "(eats some Seed) and (livesIn some Water)", "Pet", false, null, null);

        assertEquals(1, listed().size());
        assertEquals(GeneralClassAxioms.SUB_CLASS_OF, listed().get(0).type());
        assertTrue(listed().get(0).value().endsWith(" SubClassOf Pet"), listed().get(0).value());
    }

    @Test
    void aListedGeneralClassAxiomCanBeDeletedByItsValue() throws Exception {
        service.addGeneralClassAxiom("p", "(eats some Seed) and (livesIn some Water)", "Pet", false, null, null);
        service.addGeneralClassAxiom("p", "eats some Seed", "Bird", false, null, null);

        String value = listed().stream().filter(e -> e.value().endsWith("SubClassOf Bird")).findFirst().orElseThrow().value();

        assertTrue(service.deleteGeneralClassAxiom("p", value, false, null, null));
        assertEquals(1, listed().size());
        assertTrue(listed().get(0).value().endsWith("SubClassOf Pet"));
        assertFalse(service.deleteGeneralClassAxiom("p", value, false, null, null));
    }

    @Test
    void equivalenceAndDisjointnessBetweenExpressionsAreListedAndDeletable() throws Exception {
        Set<org.semanticweb.owlapi.model.OWLAxiom> axioms = Set.of(
                factory.getOWLEquivalentClassesAxiom(
                        factory.getOWLObjectSomeValuesFrom(property("eats"), clazz("Seed")),
                        factory.getOWLObjectIntersectionOf(clazz("Bird"), factory.getOWLObjectSomeValuesFrom(property("livesIn"), clazz("Water")))),
                factory.getOWLDisjointClassesAxiom(
                        factory.getOWLObjectSomeValuesFrom(property("eats"), clazz("Seed")),
                        factory.getOWLObjectSomeValuesFrom(property("livesIn"), clazz("Water"))));
        assertTrue(mutator.tryApply("p", OwlAxiomSparqlWriter.toInsertData(axioms)));

        assertEquals(Set.of(GeneralClassAxioms.EQUIVALENT_TO, GeneralClassAxioms.DISJOINT_WITH),
                listed().stream().map(GeneralClassAxioms.Entry::type).collect(java.util.stream.Collectors.toSet()));

        for (GeneralClassAxioms.Entry entry : List.copyOf(listed())) {
            assertTrue(service.deleteGeneralClassAxiom("p", entry.value(), false, null, null), entry.value());
        }
        assertTrue(listed().isEmpty());
    }

    @Test
    void addingAGeneralClassAxiomIsUndoneAndRedoneInTheInMemoryOntology() throws Exception {
        Model before = snapshot();
        service.addGeneralClassAxiom("p", "(eats some Seed) and (livesIn some Water)", "Pet", false, null, null);
        Model changed = snapshot();
        ExactDelta delta = ExactOrigins.deltaBetween(before, changed).orElseThrow();
        assertEquals(1, listed().size());

        assertTrue(mutator.tryApply("p", PREFIXES + DeltaSparql.reverse(delta)), "undo could not be applied");
        assertTrue(Models.isomorphic(before, snapshot()));
        assertTrue(listed().isEmpty());

        assertTrue(mutator.tryApply("p", PREFIXES + DeltaSparql.forward(delta)), "redo could not be applied");
        assertTrue(Models.isomorphic(changed, snapshot()));
        assertEquals(1, listed().size());
    }
}
