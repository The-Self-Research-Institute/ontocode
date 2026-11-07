package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.swrl.model.ExecutionResult;
import self.research.ontology.swrl.model.InferredAxiom;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static self.research.ontology.swrl.service.TriageHarness.rule;

class SingleRuleTestRunTest {

    private static final String CHILD = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Child(?p)";

    private static final String EVERY_PATIENT = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:greaterThanOrEqual(?a, 0) -> Child(?p)";

    @Test
    void aTestRunListsOnlyWhatTheTestedRuleConcludes() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "Age under 18", CHILD));

        ExecutionResult result = harness.service.testSingleRuleById(TriageHarness.PROJECT, "r1");

        assertTrue(result.isSuccess());
        assertFalse(result.getInferredAxioms().isEmpty());
        for (InferredAxiom axiom : result.getInferredAxioms()) {
            assertEquals("conclusion", axiom.getCategory());
            assertEquals("ClassAssertion", axiom.getAxiomType());
            assertTrue(axiom.getDescription().contains("Child"), axiom.getDescription());
        }
        assertEquals(result.getInferredAxioms().size(), result.getInferredAxiomsCount());
        assertEquals(result.getInferredAxiomsCount(), result.getConclusionCount());
        assertEquals(0, result.getStructureCount());
        assertFalse(result.isTruncated());
    }

    @Test
    void aTestRunNamesTheOneRuleAndIsLabelledAsATest() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "Age under 18", CHILD));

        ExecutionResult result = harness.service.testSingleRuleById(TriageHarness.PROJECT, "r1");

        assertEquals("test", result.getExecutionMode());
        assertEquals(List.of("Age under 18"), result.getExecutedRuleNames());
        assertEquals(List.of("r1"), result.getExecutedRuleIds());
    }

    @Test
    void aTestRunMatchesWhatExecuteConcludesForTheSameSingleRule() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "Age under 18", CHILD));

        ExecutionResult test = harness.service.testSingleRuleById(TriageHarness.PROJECT, "r1");
        ExecutionResult executed = harness.service.executeRules(TriageHarness.PROJECT);

        assertEquals(executed.getConclusionCount(), test.getConclusionCount());
    }

    @Test
    void aCappedTestRunSaysSoAndKeepsTheRealCount() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "Everyone", EVERY_PATIENT));
        int full = harness.service.testSingleRuleById(TriageHarness.PROJECT, "r1").getConclusionCount();
        assertTrue(full > 1, "the demo data needs more than one child");
        ReflectionTestUtils.setField(harness.service, "maxInferredAxioms", 1);

        ExecutionResult result = harness.service.testSingleRuleById(TriageHarness.PROJECT, "r1");

        assertEquals(1, result.getInferredAxioms().size());
        assertEquals(full, result.getConclusionCount());
        assertTrue(result.isTruncated());
    }
}
