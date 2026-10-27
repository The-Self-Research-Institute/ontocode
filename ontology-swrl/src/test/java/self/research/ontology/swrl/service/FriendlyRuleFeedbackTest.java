package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.swrl.model.ExecutionResult;
import self.research.ontology.swrl.model.ValidationResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static self.research.ontology.swrl.service.TriageHarness.rule;

class FriendlyRuleFeedbackTest {

    private static final String CHILD = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Child(?p)";
    private static final String UNKNOWN_CLASS = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Childd(?p)";

    @Test
    void aRuleUsingAnUnknownNameIsSkippedWithAReasonAndTheOthersStillRun() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "Age under 18", CHILD), rule("r2", "Typo rule", UNKNOWN_CLASS));

        ExecutionResult result = harness.service.executeRules(TriageHarness.PROJECT);

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertEquals("completed_with_warnings", result.getCompletion());
        assertEquals(List.of("Age under 18"), result.getExecutedRuleNames());
        assertEquals(1, result.getSkippedRules().size());
        assertEquals("Typo rule", result.getSkippedRules().get(0).ruleName());
        String reason = result.getSkippedRules().get(0).reason();
        assertTrue(reason.contains("'Childd'"), reason);
        assertTrue(reason.contains("Did you mean: Child"), reason);
    }

    @Test
    void whenEveryRuleRunsTheRunIsCompleteWithNothingSkipped() throws Exception {
        TriageHarness harness = new TriageHarness(rule("r1", "Age under 18", CHILD));

        ExecutionResult result = harness.service.executeRules(TriageHarness.PROJECT);

        assertEquals("completed", result.getCompletion());
        assertTrue(result.getSkippedRules().isEmpty());
        assertEquals(List.of("Age under 18"), result.getExecutedRuleNames());
    }

    @Test
    void validatingAnUnknownNameSuggestsTheClosestExistingOne() throws Exception {
        TriageHarness harness = new TriageHarness();

        ValidationResult result = harness.service.validateRule(TriageHarness.PROJECT, UNKNOWN_CLASS);

        assertFalse(result.isValid());
        assertEquals("Unknown class or property 'Childd'", result.getErrorMessage());
        assertEquals("Did you mean: Child?", result.getSuggestions().get(0));
    }

    @Test
    void aTypeMismatchIsExplainedInPlainWords() throws Exception {
        String heightIntoMod = "Patient(?p) ^ hasHeight(?p, ?h) ^ swrlb:mod(?r, ?h, 7) ^ swrlb:equal(?r, 0) -> WeeklyReview(?p)";
        TriageHarness harness = new TriageHarness(rule("r1", "Bad maths", heightIntoMod));

        ExecutionResult result = harness.service.executeRules(TriageHarness.PROJECT);

        assertFalse(result.isSuccess());
        assertEquals("failed", result.getCompletion());
        String message = result.getErrorMessage();
        assertTrue(message.contains("needs a whole number"), message);
        assertTrue(message.contains("Technical detail:"), message);
    }

    @Test
    void textWrittenWithoutATypeStillMatches() throws Exception {
        String bare = "Patient(?p) ^ inWard(?p, ?w) ^ swrlb:equal(?w, \"ICU\") -> Critical(?p)";
        TriageHarness harness = new TriageHarness(rule("r1", "ICU ward", bare));

        ExecutionResult result = harness.service.executeRules(TriageHarness.PROJECT);

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertTrue(result.getInferredAxioms().stream().anyMatch(axiom -> String.valueOf(axiom.getDescription()).contains("Critical")
                && String.valueOf(axiom.getDescription()).contains("bob")), "bob should be Critical");
    }
}
