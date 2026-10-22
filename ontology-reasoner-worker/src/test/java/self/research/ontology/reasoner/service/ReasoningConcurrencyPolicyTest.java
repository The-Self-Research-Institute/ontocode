package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.reasoner.model.ReasoningJob;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class ReasoningConcurrencyPolicyTest {

    private final EditorClient editorClient = QueueTestSupport.editorClient();
    private final ReasoningConcurrencyPolicy policy = QueueTestSupport.policy(editorClient);

    @Test
    void jobThatWouldOverrunTheSlotBudgetWaits() {
        List<ReasoningJob> active = List.of(job(1));

        assertFalse(policy.canStartAnother(active, job(2)));
        assertTrue(policy.canStartAnother(active, job(1)));
    }

    @Test
    void heavyJobStillStartsWhenNothingElseIsRunning() {
        ReflectionTestUtils.setField(policy, "slotBudget", 1);

        assertTrue(policy.canStartAnother(List.of(), job(2)));
    }

    @Test
    void secondJobWaitsWhileAnImportIsRunning() {
        when(editorClient.getActiveImportCount()).thenReturn(1);

        assertTrue(policy.canStartAnother(List.of(), job(1)));
        assertFalse(policy.canStartAnother(List.of(job(1)), job(1)));
    }

    @Test
    void importsAreIgnoredWhenYieldingIsTurnedOff() {
        ReflectionTestUtils.setField(policy, "yieldToImports", false);
        when(editorClient.getActiveImportCount()).thenReturn(1);

        assertTrue(policy.canStartAnother(List.of(job(1)), job(1)));
    }

    @Test
    void lowHeapStopsExtraJobsButNotTheFirstOne() {
        ReflectionTestUtils.setField(policy, "memoryAware", true);
        ReflectionTestUtils.setField(policy, "minFreeHeapRatio", 1.1);

        assertTrue(policy.canStartAnother(List.of(), job(1)));
        assertFalse(policy.canStartAnother(List.of(job(1)), job(1)));
    }

    @Test
    void decisionMatchesTheSnapshotCeiling() {
        when(editorClient.getActiveImportCount()).thenReturn(1);
        List<ReasoningJob> active = List.of(job(1));

        ReasoningConcurrencyPolicy.ConcurrencySnapshot snapshot = policy.snapshot(active);

        assertTrue(snapshot.effectiveMax() <= active.size());
        assertFalse(policy.canStartAnother(active, job(1)));
    }

    private static ReasoningJob job(int slotWeight) {
        return ReasoningJob.builder().jobId("job-" + slotWeight + "-" + System.nanoTime()).slotWeight(slotWeight).build();
    }
}
