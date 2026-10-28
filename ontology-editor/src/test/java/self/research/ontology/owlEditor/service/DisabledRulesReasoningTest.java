package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.common.rules.DisabledRules;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisabledRulesReasoningTest {

    static final String NS = "http://example.org/dr#";

    ReasonerService service;
    OWLDataFactory df;

    @BeforeEach
    void setUp() {
        service = new ReasonerService();
        ReflectionTestUtils.setField(service, "reasonerOperationTimeoutMs", 30_000L);
        df = OWLManager.getOWLDataFactory();
    }

    @AfterEach
    void tearDown() {
        service.clearCache();
    }

    OWLOntology ontologyWithRule(boolean enabled) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/dr" + enabled));
        OWLClass student = df.getOWLClass(IRI.create(NS + "Student"));
        OWLClass person = df.getOWLClass(IRI.create(NS + "Person"));
        OWLNamedIndividual alice = df.getOWLNamedIndividual(IRI.create(NS + "alice"));
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(student, alice));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(person));
        var x = df.getSWRLVariable(IRI.create(NS + "x"));
        Set<OWLAnnotation> notes = enabled ? Set.of() : Set.of(df.getOWLAnnotation(
                df.getOWLAnnotationProperty(DisabledRules.ENABLED), df.getOWLLiteral(false)));
        manager.addAxiom(ontology, df.getSWRLRule(
                Set.of(df.getSWRLClassAtom(student, x)), Set.of(df.getSWRLClassAtom(person, x)), notes));
        return ontology;
    }

    boolean aliceIsPerson(OWLOntology ontology) {
        try (ReasonerLease lease = service.lease(ontology, ReasonerType.HERMIT)) {
            OWLReasoner reasoner = lease.reasoner();
            OWLNamedIndividual alice = df.getOWLNamedIndividual(IRI.create(NS + "alice"));
            return reasoner.getTypes(alice, false).containsEntity(df.getOWLClass(IRI.create(NS + "Person")));
        }
    }

    @Test
    void anEnabledRuleRunsInTheReasoner() throws Exception {
        assertTrue(aliceIsPerson(ontologyWithRule(true)));
    }

    @Test
    void aDisabledRuleHasNoEffectOnTheReasoner() throws Exception {
        assertFalse(aliceIsPerson(ontologyWithRule(false)));
    }

    @Test
    void theOntologyUsedForReasoningDropsTheDisabledRuleButKeepsTheEnabledOne() throws Exception {
        OWLOntology ontology = ontologyWithRule(false);
        assertTrue(DisabledRules.strip(ontology) == 1);
        assertTrue(ontology.axioms(org.semanticweb.owlapi.model.AxiomType.SWRL_RULE).findAny().isEmpty());
        OWLOntology enabled = ontologyWithRule(true);
        assertTrue(DisabledRules.strip(enabled) == 0);
        assertTrue(enabled.axioms(org.semanticweb.owlapi.model.AxiomType.SWRL_RULE).findAny().isPresent());
    }
}
