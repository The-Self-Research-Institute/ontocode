package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.swrl.dto.ExplainRequest;
import self.research.ontology.swrl.dto.ExplainResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static self.research.ontology.swrl.service.TriageHarness.rule;

class ExplainConclusionTest {

    private static final String NS = "http://example.org/triage#";
    private static final String TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String CHILD = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Child(?p)";
    private static final String SHORT = "Patient(?p) ^ hasHeight(?p, ?h) ^ swrlb:lessThan(?h, 1.4) -> Child(?p)";
    private static final String POWER = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:pow(?x, ?a, 2) ^ swrlb:lessThan(?x, 400) -> Child(?p)";
    private static final String SENIOR = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:greaterThanOrEqual(?a, 65) -> Senior(?p)";

    private static ExplainRequest type(String subject, String cls) {
        return new ExplainRequest(NS + subject, TYPE, NS + cls, null);
    }

    @Test
    void aRuleConclusionIsExplainedWithTheValuesThatMadeItTrue() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "Age under 18", CHILD));

        ExplainResponse response = harness.service.explainConclusion(TriageHarness.PROJECT, type("cara", "Child"), null);

        assertEquals("explained", response.status());
        assertEquals("Child(cara)", response.conclusion());
        assertEquals(1, response.justifications().size());
        ExplainResponse.Justification justification = response.justifications().get(0);
        assertEquals("Age under 18", justification.ruleName());
        assertEquals("cara", justification.bindings().get("p"));
        assertEquals("9", justification.bindings().get("a"));
        assertTrue(justification.premises().stream().anyMatch(p -> p.text().equals("hasAge(cara, 9)") && p.kind().equals("asserted fact")));
        assertTrue(justification.premises().stream().anyMatch(p -> p.text().equals("9 lessThan 18")));
    }

    @Test
    void everyRuleThatAppliesGetsItsOwnJustification() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "Age under 18", CHILD), rule("r2", "Short stature", SHORT));

        ExplainResponse response = harness.service.explainConclusion(TriageHarness.PROJECT, type("cara", "Child"), null);

        assertEquals("explained", response.status());
        assertEquals(2, response.justifications().size());
        assertTrue(response.justifications().stream().anyMatch(j -> j.ruleName().equals("Age under 18")));
        assertTrue(response.justifications().stream().anyMatch(j -> j.ruleName().equals("Short stature")
                && "1.30".equals(j.bindings().get("h"))));
    }

    @Test
    void aRuleWithAnUnsupportedBuiltInIsReportedAsNotExplainedAndTheOriginIsUnknown() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "Squared age", POWER));

        ExplainResponse response = harness.service.explainConclusion(TriageHarness.PROJECT, type("cara", "Child"), null);

        assertTrue(response.justifications().isEmpty());
        assertTrue(response.notes().stream().anyMatch(n -> n.contains("Squared age") && n.contains("not explained: unsupported built-in pow")), response.notes().toString());
        assertEquals("origin_only", response.status());
        assertTrue(response.notes().contains("Produced by OWL reasoning or a rule this explanation cannot evaluate"));
    }

    @Test
    void aFactThatOnlyOwlReasoningProducesIsOriginOnly() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "Senior age", SENIOR));

        ExplainResponse senior = harness.service.explainConclusion(TriageHarness.PROJECT, type("ann", "Senior"), null);
        ExplainResponse highRisk = harness.service.explainConclusion(TriageHarness.PROJECT, type("ann", "HighRisk"), null);

        assertEquals("explained", senior.status());
        assertEquals("origin_only", highRisk.status(), highRisk.toString());
        assertTrue(highRisk.justifications().isEmpty());
        assertTrue(highRisk.notes().contains("Produced by OWL reasoning or a rule this explanation cannot evaluate"));
    }

    @Test
    void anAssertedFactIsSaidToBeAsserted() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "Age under 18", CHILD));

        ExplainResponse response = harness.service.explainConclusion(TriageHarness.PROJECT, type("cara", "Patient"), null);

        assertEquals("origin_only", response.status());
        assertTrue(response.notes().contains("asserted fact"));
    }

    @Test
    void aFactThatIsNotTrueAtAllIsNotFound() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "Age under 18", CHILD));

        ExplainResponse response = harness.service.explainConclusion(TriageHarness.PROJECT, type("ann", "Child"), null);

        assertEquals("not_found", response.status());
        assertTrue(response.justifications().isEmpty());
    }
}
