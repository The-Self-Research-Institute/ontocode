package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import self.research.ontology.swrl.dto.WhyNotRequest;
import self.research.ontology.swrl.dto.WhyNotResponse;
import self.research.ontology.swrl.model.SwrlRule;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static self.research.ontology.swrl.service.TriageHarness.rule;

class WhyNotFiredTest {

    private static final String NS = "http://example.org/triage#";

    private static TriageHarness harness(SwrlRule target) throws Exception {
        TriageHarness harness = new TriageHarness();
        Mockito.when(harness.repository.findByIdAndProjectId(target.getId(), TriageHarness.PROJECT)).thenReturn(Optional.of(target));
        Mockito.when(harness.client.fetchOntologyWithStamp(Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> new OntologyClientService.FetchedOntology(withIvy(), 1L, null));
        return harness;
    }

    private static OWLOntology withIvy() throws Exception {
        OWLOntology ontology = TriageHarness.triage();
        OWLDataFactory factory = OWLManager.getOWLDataFactory();
        OWLNamedIndividual ivy = factory.getOWLNamedIndividual(IRI.create(NS + "ivy"));
        ontology.getOWLOntologyManager().addAxiom(ontology,
                factory.getOWLClassAssertionAxiom(factory.getOWLClass(IRI.create(NS + "Patient")), ivy));
        return ontology;
    }

    private static WhyNotResponse ask(SwrlRule rule, String who) throws Exception {
        return harness(rule).service.whyNotFired(TriageHarness.PROJECT, rule.getId(), new WhyNotRequest(NS + who), null);
    }

    @Test
    void aMissingFactIsReportedAsNoSupportingFactNotAsFalse() throws Exception {
        SwrlRule rule = rule("r1", "Age under 18", "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Child(?p)");

        WhyNotResponse response = ask(rule, "ivy");

        assertEquals("no_supporting_fact", response.result());
        assertEquals("satisfied", response.atoms().get(0).status());
        assertEquals("failed", response.atoms().get(1).status());
        assertTrue(response.atoms().get(1).detail().contains("ivy has no hasAge fact"), response.atoms().get(1).detail());
        assertEquals("not_evaluated", response.atoms().get(2).status());
        assertTrue(response.scope().contains("named individual"));
    }

    @Test
    void aFailedComparisonShowsTheActualValues() throws Exception {
        SwrlRule rule = rule("r1", "Over nine", "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:greaterThan(?a, 9) -> Senior(?p)");

        WhyNotResponse response = ask(rule, "cara");

        assertEquals("comparison_failed", response.result());
        WhyNotResponse.AtomReport failed = response.atoms().stream().filter(a -> a.status().equals("failed")).findFirst().orElseThrow();
        assertEquals("9 is not greaterThan 9", failed.detail());
        assertEquals("9", response.bindings().get("a"));
    }

    @Test
    void aRuleThatCanFireIsReportedAsFiring() throws Exception {
        SwrlRule rule = rule("r1", "Age under 18", "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Child(?p)");

        WhyNotResponse response = ask(rule, "cara");

        assertEquals("fires", response.result());
        assertTrue(response.atoms().stream().allMatch(a -> a.status().equals("satisfied")));
        assertEquals("9", response.bindings().get("a"));
    }

    @Test
    void aBuiltInOutsideTheSubsetIsNamedAsUnsupported() throws Exception {
        SwrlRule rule = rule("r1", "Squared age", "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:pow(?x, ?a, 2) ^ swrlb:lessThan(?x, 400) -> Child(?p)");

        WhyNotResponse response = ask(rule, "cara");

        assertEquals("unsupported", response.result());
        assertTrue(response.summary().contains("built-in pow"), response.summary());
        assertTrue(response.atoms().stream().anyMatch(a -> a.status().equals("unsupported") && a.text().contains("pow")));
    }

    @Test
    void whenSeveralBindingsExistTheyAreTriedAndAnyCompleteOneMakesItFire() throws Exception {
        SwrlRule rule = rule("r1", "Two facts", "Patient(?p) ^ hasAge(?p, ?a) ^ hasTemp(?p, ?t) ^ swrlb:greaterThan(?t, 38.0) -> Fever(?p)");

        assertEquals("fires", ask(rule, "ann").result());
        assertEquals("comparison_failed", ask(rule, "bob").result());
    }
}
