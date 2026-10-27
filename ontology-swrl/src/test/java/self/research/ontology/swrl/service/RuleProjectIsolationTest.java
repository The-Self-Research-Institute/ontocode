package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import self.research.ontology.swrl.model.ExecutionResult;
import self.research.ontology.swrl.model.SwrlRule;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static self.research.ontology.swrl.service.TriageHarness.rule;

class RuleProjectIsolationTest {

    private static final String CHILD = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Child(?p)";

    private SwrlRule ruleOfAnotherProject() {
        SwrlRule other = new SwrlRule("other-project", "Their rule", CHILD);
        other.setId("theirs");
        other.setEnabled(true);
        return other;
    }

    @Test
    void aRuleCannotBeEditedThroughAnotherProjectsAddress() throws Exception {
        TriageHarness harness = new TriageHarness();
        Mockito.when(harness.repository.findByIdAndProjectId("theirs", "other-project")).thenReturn(Optional.of(ruleOfAnotherProject()));

        assertThrows(IllegalArgumentException.class,
                () -> harness.service.updateRule(TriageHarness.PROJECT, "theirs", CHILD, null, false, null));

        Mockito.verify(harness.repository, Mockito.never()).save(Mockito.any(SwrlRule.class));
    }

    @Test
    void aRuleCannotBeDeletedThroughAnotherProjectsAddress() throws Exception {
        TriageHarness harness = new TriageHarness();
        Mockito.when(harness.repository.findByIdAndProjectId("theirs", "other-project")).thenReturn(Optional.of(ruleOfAnotherProject()));

        assertThrows(IllegalArgumentException.class, () -> harness.service.deleteRule(TriageHarness.PROJECT, "theirs"));

        Mockito.verify(harness.repository, Mockito.never()).delete(Mockito.any(SwrlRule.class));
        Mockito.verify(harness.repository, Mockito.never()).deleteById(Mockito.anyString());
    }

    @Test
    void aBatchDeleteSkipsRulesOfOtherProjects() throws Exception {
        TriageHarness harness = new TriageHarness();
        Mockito.when(harness.repository.findByIdAndProjectId("theirs", "other-project")).thenReturn(Optional.of(ruleOfAnotherProject()));

        harness.service.deleteRulesBatch(TriageHarness.PROJECT, List.of("theirs"));

        Mockito.verify(harness.repository, Mockito.never()).delete(Mockito.any(SwrlRule.class));
    }

    @Test
    void aRuleCannotBeDuplicatedThroughAnotherProjectsAddress() throws Exception {
        TriageHarness harness = new TriageHarness();
        Mockito.when(harness.repository.findByIdAndProjectId("theirs", "other-project")).thenReturn(Optional.of(ruleOfAnotherProject()));

        assertThrows(IllegalArgumentException.class, () -> harness.service.duplicateRule(TriageHarness.PROJECT, "theirs", "Copy"));

        Mockito.verify(harness.repository, Mockito.never()).save(Mockito.any(SwrlRule.class));
    }

    @Test
    void executeSelectedNeverRunsARuleOfAnotherProject() throws Exception {
        TriageHarness harness = new TriageHarness();
        SwrlRule mine = rule("mine", "Age under 18", CHILD);
        Mockito.when(harness.repository.findAllById(List.of("mine", "theirs"))).thenReturn(List.of(mine, ruleOfAnotherProject()));

        ExecutionResult result = harness.service.executeSelectedRules(TriageHarness.PROJECT, List.of("mine", "theirs"));

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertEquals(List.of("Age under 18"), result.getExecutedRuleNames());
        assertEquals(1, result.getSkippedRules().size());
        assertEquals("theirs", result.getSkippedRules().get(0).ruleName());
        assertEquals("This rule was not found in this project.", result.getSkippedRules().get(0).reason());
        assertEquals("completed_with_warnings", result.getCompletion());
    }

    @Test
    void executeSelectedWithOnlyForeignRulesFailsAndSaysWhy() throws Exception {
        TriageHarness harness = new TriageHarness();
        Mockito.when(harness.repository.findAllById(List.of("theirs"))).thenReturn(List.of(ruleOfAnotherProject()));

        ExecutionResult result = harness.service.executeSelectedRules(TriageHarness.PROJECT, List.of("theirs"));

        assertEquals(false, result.isSuccess());
        assertEquals("failed", result.getCompletion());
        assertEquals(1, result.getSkippedRules().size());
        assertEquals("This rule was not found in this project.", result.getSkippedRules().get(0).reason());
    }
}
