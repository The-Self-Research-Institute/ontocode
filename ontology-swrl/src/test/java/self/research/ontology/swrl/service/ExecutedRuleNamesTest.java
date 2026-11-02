package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.SWRLRule;
import self.research.ontology.swrl.model.ExecutionResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static self.research.ontology.swrl.service.TriageHarness.rule;

class ExecutedRuleNamesTest {

    private static final String CHILD = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Child(?p)";
    private static final String SENIOR = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:greaterThanOrEqual(?a, 65) -> Senior(?p)";

    @Test
    void aLabelledRuleIsReportedByItsLabelAndItsIdIsKeptSeparately() throws Exception {
        ExecutionResult result = new TriageHarness(rule("r1", "HighFever", CHILD), rule("r2", "Senior age", SENIOR)).service.executeRules("p1");

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertEquals(List.of("HighFever", "Senior age"), result.getExecutedRuleNames().stream().sorted().toList());
        assertEquals(2, result.getExecutedRuleIds().size());
        assertEquals(List.of("r1", "r2"), result.getExecutedRuleIds().stream().sorted().toList());
    }

    @Test
    void anUnlabelledRuleGetsTheGeneratedRuleNameAndNeverTheRuleDashId() throws Exception {
        TriageHarness source = new TriageHarness(rule("r1", "temp", CHILD));
        SWRLRule labelled = source.fresh().getAxioms(org.semanticweb.owlapi.model.AxiomType.SWRL_RULE).iterator().next();
        TriageHarness harness = new TriageHarness();
        harness.addAxiom((SWRLRule) labelled.getAxiomWithoutAnnotations());

        ExecutionResult result = harness.service.executeRules("p1");

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertEquals(1, result.getExecutedRuleNames().size());
        String name = result.getExecutedRuleNames().get(0);
        assertTrue(name.matches("Rule [0-9a-f]{8}"), name);
        assertFalse(name.startsWith("rule-"));
        assertTrue(result.getExecutedRuleIds().get(0).startsWith("rule-" + name.substring(5)));
    }

    @Test
    void duplicateLabelsAreBothReportedByLabelWithDistinctIds() throws Exception {
        ExecutionResult result = new TriageHarness(rule("c1", "Same", CHILD), rule("c2", "Same", SENIOR)).service.executeRules("p1");

        assertEquals(List.of("Same", "Same"), result.getExecutedRuleNames());
        assertEquals(2, result.getExecutedRuleIds().stream().distinct().count());
    }

    @Test
    void selectedRunReportsNamesAndIdsInTheSameOrder() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "HighFever", CHILD), rule("r2", "Senior age", SENIOR));
        String id = harness.service.executeRules("p1").getExecutedRuleIds().get(0);

        ExecutionResult result = harness.service.executeSelectedRules("p1", List.of(id));

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertEquals(1, result.getExecutedRuleNames().size());
        assertEquals(List.of(id), result.getExecutedRuleIds());
        assertFalse(result.getExecutedRuleNames().get(0).startsWith("rule-"));
    }

    @Test
    void skippedRulesUseNames() throws Exception {
        ExecutionResult result = new TriageHarness(rule("r1", "HighFever", CHILD)).service.executeSelectedRules("p1", List.of("HighFever", "Missing"));

        assertTrue(result.getSkippedRules().stream().anyMatch(s -> s.ruleName().equals("Missing")));
        assertTrue(result.getExecutedRuleNames().contains("HighFever"));
    }
}
