package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.swrl.model.ExecutionResult;
import self.research.ontology.swrl.model.SwrlRule;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static self.research.ontology.swrl.service.TriageHarness.rule;

class RuleNameClashTest {

    private static final String WEEKLY = "Patient(?p) ^ daysAdmitted(?p, ?d) ^ swrlb:mod(?r, ?d, 7) ^ swrlb:equal(?r, 0) -> WeeklyReview(?p)";
    private static final String CHILD = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Child(?p)";

    private ExecutionResult execute(SwrlRule... rules) throws Exception {
        return new TriageHarness(rules).service.executeRules("p1");
    }

    @Test
    void aRuleNamedLikeAClassDoesNotBreakOtherRules() throws Exception {
        ExecutionResult result = execute(
                rule("a1", "Child age", CHILD),
                rule("a2", "Weekly review days mod 7", WEEKLY));
        assertTrue(result.isSuccess(), "execution failed: " + result.getErrorMessage());
        assertEquals(List.of("Child age", "Weekly review days mod 7"), result.getExecutedRuleNames().stream().sorted().toList());
    }

    @Test
    void sameRulesUnderOtherNamesStillWork() throws Exception {
        ExecutionResult result = execute(
                rule("b1", "Age under 18", CHILD),
                rule("b2", "Weekly review days mod 7", WEEKLY));
        assertTrue(result.isSuccess(), "execution failed: " + result.getErrorMessage());
    }

    @Test
    void twoRulesWithTheSameNameAreBothExecuted() throws Exception {
        ExecutionResult result = execute(
                rule("c1", "Same", CHILD),
                rule("c2", "Same", WEEKLY));
        assertTrue(result.isSuccess(), "execution failed: " + result.getErrorMessage());
        assertEquals(List.of("Same", "Same"), result.getExecutedRuleNames());
    }

    @Test
    void rulesEmbeddedInTheFileNowRunDuringExecute() throws Exception {
        ExecutionResult result = TriageHarness.withEmbeddedRules().service.executeRules("p1");
        assertTrue(result.isSuccess(), "execution failed: " + result.getErrorMessage());
        long fromEmbedded = result.getInferredAxioms().stream()
                .filter(a -> String.valueOf(a.getDescription()).contains("ClassAssertion")
                        && (String.valueOf(a.getDescription()).contains("#Child>") || String.valueOf(a.getDescription()).contains("#WeeklyReview>")))
                .count();
        assertTrue(fromEmbedded > 0, "rules inside the ontology must run during Execute");
        assertEquals(12, result.getExecutedRuleNames().size());
    }

    @Test
    void aDisabledRuleIsNotExecuted() throws Exception {
        SwrlRule off = rule("d1", "Child age", CHILD);
        off.setEnabled(false);
        ExecutionResult result = execute(off, rule("d2", "Weekly review days mod 7", WEEKLY));
        assertTrue(result.isSuccess(), "execution failed: " + result.getErrorMessage());
        assertEquals(List.of("Weekly review days mod 7"), result.getExecutedRuleNames());
        assertTrue(result.getInferredAxioms().stream().noneMatch(a -> String.valueOf(a.getDescription()).contains("ClassAssertion") && String.valueOf(a.getDescription()).contains("#Child>")));
    }

    @Test
    void validatingRulesThatAlsoExistInTheFileWorks() throws Exception {
        OntologyClientService client = Mockito.mock(OntologyClientService.class);
        SwrlEngineService service = new SwrlEngineService();
        ReflectionTestUtils.setField(service, "ontologyClient", client);
        ReflectionTestUtils.setField(service, "maxInferredAxioms", 1000);
        ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 60);
        for (String text : new String[]{CHILD, WEEKLY}) {
            OWLOntology fresh = OWLManager.createOWLOntologyManager()
                    .loadOntologyFromOntologyDocument(new StringDocumentSource(TriageHarness.triageText()));
            Mockito.when(client.fetchOntology(Mockito.anyString())).thenReturn(fresh);
            var result = service.validateRule("p1", text);
            assertTrue(result.isValid(), "validation said: " + result.getErrorMessage());
        }
    }
}
