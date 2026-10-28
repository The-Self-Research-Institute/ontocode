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

class ResultPresentationTest {

    private static final String CHILD = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Child(?p)";

    @Test
    void ruleConclusionsComeFirstAndAreMarkedAsConclusions() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "Age under 18", CHILD));

        ExecutionResult result = harness.service.executeRules(TriageHarness.PROJECT);

        List<InferredAxiom> axioms = result.getInferredAxioms();
        assertTrue(result.getConclusionCount() >= 1);
        assertEquals("conclusion", axioms.get(0).getCategory());
        assertTrue(axioms.get(0).getDescription().contains("Child"), axioms.get(0).getDescription());
        int firstStructure = -1;
        for (int i = 0; i < axioms.size(); i++) {
            if ("structure".equals(axioms.get(i).getCategory())) {
                firstStructure = i;
                break;
            }
        }
        assertEquals(result.getConclusionCount(), firstStructure < 0 ? axioms.size() : firstStructure);
        assertTrue(axioms.stream().limit(result.getConclusionCount()).allMatch(a -> "conclusion".equals(a.getCategory())));
    }

    @Test
    void trivialSameIndividualFactsAreDroppedAndCounted() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "Age under 18", CHILD));

        ExecutionResult result = harness.service.executeRules(TriageHarness.PROJECT);

        assertFalse(result.getInferredAxioms().stream().anyMatch(a -> "SameIndividual".equals(a.getAxiomType())
                && a.getDescription().matches(".*SameIndividual\\((<[^>]+>) \\1\\).*")));
    }

    @Test
    void factsAlreadyInTheOntologyAreNotReportedAsConclusions() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "Age under 18", CHILD));

        ExecutionResult result = harness.service.executeRules(TriageHarness.PROJECT);

        assertTrue(result.getInferredAxioms().stream().filter(a -> "conclusion".equals(a.getCategory()))
                .noneMatch(a -> a.getDescription().contains("ClassAssertion(<http://example.org/triage#Patient>")), "asserted facts must not be listed as new");
    }

    @Test
    void theCapOnlyCutsStructureAxiomsNeverRuleConclusions() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "Age under 18", CHILD));
        ReflectionTestUtils.setField(harness.service, "maxInferredAxioms", 2);

        ExecutionResult result = harness.service.executeRules(TriageHarness.PROJECT);

        assertEquals(2, result.getInferredAxioms().size());
        assertTrue(result.isTruncated());
        assertTrue(result.getInferredAxiomsCount() >= result.getConclusionCount());
    }
}
