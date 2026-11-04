package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import self.research.ontology.swrl.model.ExecutionResult;
import self.research.ontology.swrl.model.SwrlRule;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static self.research.ontology.swrl.service.TriageHarness.rule;

class RuleProjectIsolationTest {

    private static final String CHILD = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:lessThan(?a, 18) -> Child(?p)";
    private static final String SENIOR = "Patient(?p) ^ hasAge(?p, ?a) ^ swrlb:greaterThanOrEqual(?a, 65) -> Senior(?p)";

    @Test
    void executeSelectedRunsOnlyTheChosenRulesOfTheOntologyAndSkipsUnknownOnes() throws Exception {
        TriageHarness harness = new TriageHarness(rule("mine", "Age under 18", CHILD), rule("other", "Senior age", SENIOR));

        ExecutionResult result = harness.service.executeSelectedRules(TriageHarness.PROJECT, List.of("mine", "theirs"));

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertEquals(List.of("Age under 18"), result.getExecutedRuleNames());
        assertEquals(1, result.getSkippedRules().size());
        assertEquals("theirs", result.getSkippedRules().get(0).ruleName());
        assertEquals("This rule was not found in this project.", result.getSkippedRules().get(0).reason());
        assertEquals("completed_with_warnings", result.getCompletion());
        assertTrue(result.getInferredAxioms().stream().noneMatch(a -> String.valueOf(a.getDescription()).contains("ClassAssertion") && String.valueOf(a.getDescription()).contains("#Senior>")));
    }

    @Test
    void executeSelectedWithOnlyUnknownRulesFailsAndSaysWhy() throws Exception {
        TriageHarness harness = new TriageHarness(rule("mine", "Age under 18", CHILD));

        ExecutionResult result = harness.service.executeSelectedRules(TriageHarness.PROJECT, List.of("theirs"));

        assertEquals(false, result.isSuccess());
        assertEquals("failed", result.getCompletion());
        assertEquals(1, result.getSkippedRules().size());
        assertEquals("This rule was not found in this project.", result.getSkippedRules().get(0).reason());
    }

    @Test
    void executeSelectedSkipsADisabledRule() throws Exception {
        SwrlRule off = rule("off", "Senior age", SENIOR);
        off.setEnabled(false);
        TriageHarness harness = new TriageHarness(rule("mine", "Age under 18", CHILD), off);

        ExecutionResult result = harness.service.executeSelectedRules(TriageHarness.PROJECT, List.of("mine", "off"));

        assertEquals(List.of("Age under 18"), result.getExecutedRuleNames());
        assertEquals("This rule is disabled.", result.getSkippedRules().get(0).reason());
    }

    @Test
    void legacyRowsAreReadOnlyFromMongoAndOnlyThoseNotYetMigrated() throws Exception {
        TriageHarness harness = new TriageHarness();
        Mockito.when(harness.repository.findByProjectIdAndMigratedAtIsNull(TriageHarness.PROJECT))
                .thenReturn(List.of(rule("old", "Old rule", CHILD)));

        List<SwrlRule> rows = harness.service.legacyRules(TriageHarness.PROJECT);

        assertEquals(1, rows.size());
        assertEquals("old", rows.get(0).getId());
    }

    @Test
    void markingALegacyRowMigratedStampsItAndAnotherProjectsRowIsRefused() throws Exception {
        TriageHarness harness = new TriageHarness();
        SwrlRule mine = rule("old", "Old rule", CHILD);
        Mockito.when(harness.repository.findByIdAndProjectId("old", TriageHarness.PROJECT)).thenReturn(Optional.of(mine));
        Mockito.when(harness.repository.findByIdAndProjectId("theirs", TriageHarness.PROJECT)).thenReturn(Optional.empty());
        Mockito.when(harness.repository.save(Mockito.any(SwrlRule.class))).thenAnswer(i -> i.getArgument(0));

        SwrlRule saved = harness.service.markMigrated(TriageHarness.PROJECT, "old");

        assertNotNull(saved.getMigratedAt());
        assertTrue(saved.getMigratedAt().isBefore(LocalDateTime.now().plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> harness.service.markMigrated(TriageHarness.PROJECT, "theirs"));
        Mockito.verify(harness.repository, Mockito.times(1)).save(Mockito.any(SwrlRule.class));
    }

    @Test
    void executionNeverReadsMongo() throws Exception {
        TriageHarness harness = new TriageHarness(rule("mine", "Age under 18", CHILD));

        harness.service.executeRules(TriageHarness.PROJECT);
        harness.service.executeSelectedRules(TriageHarness.PROJECT, List.of("mine"));
        harness.service.exportEnabledRulesAsOwl(TriageHarness.PROJECT, null);
        harness.service.listEmbeddedRules(TriageHarness.PROJECT, null);

        Mockito.verifyNoInteractions(harness.repository);
    }
}
