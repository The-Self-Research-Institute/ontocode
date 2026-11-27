package self.research.ontology.common;

import org.junit.jupiter.api.Test;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.impl.OWLNamedIndividualNodeSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InferredPropertyFactsTest {

    private static final String NS = "http://example.org/people#";

    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final OWLDataFactory df = manager.getOWLDataFactory();
    private final OWLNamedIndividual ali = df.getOWLNamedIndividual(IRI.create(NS + "Ali"));
    private final OWLNamedIndividual karim = df.getOWLNamedIndividual(IRI.create(NS + "Karim"));
    private final OWLObjectProperty hasFriend = df.getOWLObjectProperty(IRI.create(NS + "hasFriend"));
    private final OWLDataProperty hasAge = df.getOWLDataProperty(IRI.create(NS + "hasAge"));
    private final OWLDataProperty hasExactAge = df.getOWLDataProperty(IRI.create(NS + "hasExactAge"));

    private OWLOntology people() throws Exception {
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/people"));
        OWLClass person = df.getOWLClass(IRI.create(NS + "Person"));
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(person, ali));
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(person, karim));
        manager.addAxiom(ontology, df.getOWLSymmetricObjectPropertyAxiom(hasFriend));
        manager.addAxiom(ontology, df.getOWLObjectPropertyAssertionAxiom(hasFriend, karim, ali,
                Set.of(df.getOWLAnnotation(df.getRDFSComment(), df.getOWLLiteral("met at work")))));
        manager.addAxiom(ontology, df.getOWLSubDataPropertyOfAxiom(hasExactAge, hasAge));
        manager.addAxiom(ontology, df.getOWLDataPropertyAssertionAxiom(hasExactAge, ali, 30));
        return ontology;
    }

    @Test
    void theInferredAxiomsListIncludesPropertyFactsTheReasonerWorkedOut() throws Exception {
        OWLOntology ontology = people();
        OWLReasoner hermit = new ReasonerFactory().createReasoner(ontology);

        Map<String, Object> snapshot = ReasoningSnapshot.build(ontology, hermit, hermit);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> axioms = (List<Map<String, Object>>) ((Map<String, Object>) snapshot.get("inferredAxioms")).get("axioms");
        List<String> readable = axioms.stream().map(a -> a.get("axiomType") + " " + a.get("readable")).toList();
        assertTrue(readable.contains("ObjectPropertyAssertion ObjectPropertyAssertion(<hasFriend> <Ali> <Karim>)"), readable.toString());
        assertTrue(readable.contains("DataPropertyAssertion DataPropertyAssertion(<hasAge> <Ali> \"30\"^^xsd:integer)"), readable.toString());
        assertTrue(readable.stream().anyMatch(r -> r.startsWith("ClassAssertion")), readable.toString());
        assertFalse(readable.contains("ObjectPropertyAssertion ObjectPropertyAssertion(<hasFriend> <Karim> <Ali>)"), readable.toString());
        assertFalse(readable.stream().anyMatch(r -> r.contains("<hasExactAge>")), readable.toString());
    }

    @Test
    void valuesAlreadyStatedInTheOntologyDoNotTakeUpTheSlotsForNewInferences() throws Exception {
        OWLOntology ontology = people();
        OWLReasoner hermit = new ReasonerFactory().createReasoner(ontology);

        InferredPropertyFacts.Result result = InferredPropertyFacts.collect(ontology, hermit, df, 100);

        assertEquals(Set.of(df.getOWLObjectPropertyAssertionAxiom(hasFriend, ali, karim),
                df.getOWLDataPropertyAssertionAxiom(hasAge, ali, 30)), result.facts());
    }

    @Test
    void factsFoundBeforeALaterLookupFailsAreKeptAndTheListIsMarkedIncomplete() throws Exception {
        OWLReasoner partial = mock(OWLReasoner.class);
        when(partial.getObjectPropertyValues(any(), any())).thenReturn(new OWLNamedIndividualNodeSet(karim));
        when(partial.getDataPropertyValues(any(), any())).thenThrow(new UnsupportedOperationException("not supported"));

        InferredPropertyFacts.Result result = InferredPropertyFacts.collect(people(), partial, df, 100);

        assertTrue(result.facts().contains(df.getOWLObjectPropertyAssertionAxiom(hasFriend, ali, karim)));
        assertTrue(result.stoppedEarly());
        assertTrue(result.note().contains("may be incomplete"), result.note());
    }

    @Test
    void aReasonerThatCannotAnswerPropertyQuestionsLeavesAClearNoteInsteadOfFailing() throws Exception {
        OWLReasoner limited = mock(OWLReasoner.class);
        when(limited.getObjectPropertyValues(any(), any())).thenThrow(new UnsupportedOperationException("not supported"));

        InferredPropertyFacts.Result result = InferredPropertyFacts.collect(people(), limited, df, 100);

        assertTrue(result.facts().isEmpty());
        assertFalse(result.stoppedEarly());
        assertTrue(result.note().contains("only class facts"), result.note());
    }

    @Test
    void lookupsStopAtTheirLimitAndSaySo() throws Exception {
        OWLOntology ontology = people();
        OWLReasoner hermit = new ReasonerFactory().createReasoner(ontology);

        InferredPropertyFacts.Result result = InferredPropertyFacts.collect(ontology, hermit, df, 100, 1, 10_000);

        assertTrue(result.stoppedEarly());
    }

    @Test
    void classAndPropertyFactsShareTheLimitSoNeitherIsCrowdedOut() {
        List<OWLAxiom> classFacts = facts("C", 10);
        List<OWLAxiom> propertyFacts = facts("P", 10);

        assertEquals(6, InferredPropertyFacts.share(classFacts, propertyFacts, 6).size());
        assertEquals(3, InferredPropertyFacts.share(classFacts, propertyFacts, 6).stream().filter(propertyFacts::contains).count());
        assertEquals(4, InferredPropertyFacts.share(classFacts.subList(0, 2), propertyFacts, 6).stream().filter(propertyFacts::contains).count());
        assertEquals(5, InferredPropertyFacts.share(classFacts.subList(0, 5), propertyFacts.subList(0, 0), 6).size());
    }

    private List<OWLAxiom> facts(String prefix, int count) {
        List<OWLAxiom> facts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            facts.add(df.getOWLClassAssertionAxiom(df.getOWLClass(IRI.create(NS + prefix + i)), ali));
        }
        return facts;
    }
}
