package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasonerServiceWarmupTest {

    private ReasonerService service;
    private OWLOntology ontology;
    private OWLClass animal;
    private OWLClass dog;

    @BeforeEach
    void setUp() throws Exception {
        service = new ReasonerService();
        ReflectionTestUtils.setField(service, "reasonerOperationTimeoutMs", 30_000L);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        ontology = manager.createOntology(IRI.create("http://example.org/warmup"));
        animal = df.getOWLClass(IRI.create("http://example.org/warmup#Animal"));
        dog = df.getOWLClass(IRI.create("http://example.org/warmup#Dog"));
        OWLNamedIndividual rex = df.getOWLNamedIndividual(IRI.create("http://example.org/warmup#rex"));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(dog, animal));
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(dog, rex));
    }

    @AfterEach
    void tearDown() {
        service.clearCache();
    }

    @Test
    void hierarchyReasonerSkipsClassAssertions() {
        OWLReasoner reasoner = service.getClassHierarchyReasoner(ontology, ReasonerType.HERMIT);

        assertTrue(reasoner.isPrecomputed(InferenceType.CLASS_HIERARCHY));
        assertFalse(reasoner.isPrecomputed(InferenceType.CLASS_ASSERTIONS));
        assertEquals(Set.of(dog), service.getInferredSubClasses(reasoner, animal, true));
    }

    @Test
    void callersThatNeedClassAssertionsStillGetThemOnTheSharedReasoner() {
        OWLReasoner hierarchyOnly = service.getClassHierarchyReasoner(ontology, ReasonerType.HERMIT);

        OWLReasoner full = service.getReasoner(ontology, ReasonerType.HERMIT);

        assertSame(hierarchyOnly, full);
        assertTrue(full.isPrecomputed(InferenceType.CLASS_ASSERTIONS));
    }

    @Test
    void fullReasonerIsReusedForTheExpandPath() {
        OWLReasoner full = service.getReasoner(ontology, ReasonerType.HERMIT);

        assertSame(full, service.getClassHierarchyReasoner(ontology, ReasonerType.HERMIT));
        assertTrue(full.isPrecomputed(InferenceType.CLASS_ASSERTIONS));
    }
}
