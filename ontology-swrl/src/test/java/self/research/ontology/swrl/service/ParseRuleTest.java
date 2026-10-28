package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLRule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParseRuleTest {

    private static final String NS = "http://example.org/triage#";
    private static final String CHILD = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Child(?p)";

    @Test
    void aValidRuleBecomesOneUnannotatedSwrlRuleAxiomInFunctionalSyntax() throws Exception {
        TriageHarness harness = new TriageHarness();

        SwrlEngineService.RuleParse parsed = harness.service.parseRule(TriageHarness.PROJECT, CHILD, null);

        assertTrue(parsed.valid(), String.valueOf(parsed.errors()));
        assertTrue(parsed.errors().isEmpty());
        OWLOntology result = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new StringDocumentSource(parsed.axiom()));
        assertEquals(1, result.getAxioms(AxiomType.SWRL_RULE).size(), parsed.axiom());
        SWRLRule rule = result.getAxioms(AxiomType.SWRL_RULE).iterator().next();
        assertTrue(rule.getAnnotations().isEmpty());
        assertTrue(result.containsClassInSignature(IRI.create(NS + "Child")));
        assertTrue(parsed.axiom().contains("swrlb#lessThan"), parsed.axiom());
    }

    @Test
    void anUnknownClassIsReportedInPlainWordsWithSuggestions() throws Exception {
        TriageHarness harness = new TriageHarness();

        SwrlEngineService.RuleParse parsed = harness.service.parseRule(TriageHarness.PROJECT,
                "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Childd(?p)", null);

        assertFalse(parsed.valid());
        assertNull(parsed.axiom());
        assertEquals(1, parsed.errors().size());
        assertTrue(parsed.errors().get(0).contains("Childd"), parsed.errors().toString());
        assertTrue(parsed.suggestions().stream().anyMatch(s -> s.contains("Did you mean")), parsed.suggestions().toString());
    }

    @Test
    void aSyntaxErrorIsReportedAndNoAxiomIsReturned() throws Exception {
        TriageHarness harness = new TriageHarness();

        SwrlEngineService.RuleParse parsed = harness.service.parseRule(TriageHarness.PROJECT, "Patient(?p -> Child(?p)", null);

        assertFalse(parsed.valid());
        assertNull(parsed.axiom());
        assertFalse(parsed.errors().isEmpty());
    }

    @Test
    void parsingNeedsNoStoredRuleAndOtherRulesInTheOntologyDoNotRun() throws Exception {
        TriageHarness harness = TriageHarness.withEmbeddedRules();

        SwrlEngineService.RuleParse parsed = harness.service.parseRule(TriageHarness.PROJECT, CHILD, null);

        assertTrue(parsed.valid(), String.valueOf(parsed.errors()));
        Mockito.verifyNoInteractions(harness.repository);
    }

    @Test
    void aDraftParseReadsTheUsersDraft() throws Exception {
        TriageHarness harness = new TriageHarness();

        SwrlEngineService.RuleParse parsed = harness.service.parseRule(TriageHarness.PROJECT, CHILD, "u1");

        assertTrue(parsed.valid(), String.valueOf(parsed.errors()));
        Mockito.verify(harness.client).fetchOntologyWithStamp(TriageHarness.PROJECT, "u1");
    }

    @Test
    void theParsedAxiomCanBeStoredInTheOntologyAndThenRuns() throws Exception {
        TriageHarness first = new TriageHarness();
        SwrlEngineService.RuleParse parsed = first.service.parseRule(TriageHarness.PROJECT, CHILD, null);
        OWLOntology result = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new StringDocumentSource(parsed.axiom()));
        SWRLRule axiom = result.getAxioms(AxiomType.SWRL_RULE).iterator().next();
        OWLOntology target = TriageHarness.bare();
        target.getOWLOntologyManager().addAxiom(target, axiom);
        TriageHarness second = new TriageHarness();
        Mockito.when(second.client.fetchOntologyWithStamp(Mockito.anyString(), Mockito.any()))
                .thenReturn(new OntologyClientService.FetchedOntology(target, 1L, null));

        var run = second.service.executeRules(TriageHarness.PROJECT);

        assertTrue(run.isSuccess(), String.valueOf(run.getErrorMessage()));
        assertEquals(1, run.getExecutedRuleNames().size());
        assertTrue(run.getInferredAxioms().stream().anyMatch(a -> String.valueOf(a.getDescription()).contains("#Child>")));
    }
}
