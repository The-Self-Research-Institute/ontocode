package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.model.DLQueryJob;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DLQueryConcurrencyPolicyHeapTest {

    private FakeMemorySignals signals;
    private DLQueryConcurrencyPolicy policy;
    private ImportQueueManager imports;

    @BeforeEach
    void setUp() {
        signals = new FakeMemorySignals();
        imports = mock(ImportQueueManager.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(imports.getQueueStats().getActiveImports()).thenReturn(0);
        policy = new DLQueryConcurrencyPolicy(imports, signals);
        ReflectionTestUtils.setField(policy, "configuredMax", 4);
        ReflectionTestUtils.setField(policy, "slotBudget", 4);
        ReflectionTestUtils.setField(policy, "memoryAware", true);
        ReflectionTestUtils.setField(policy, "minFreeHeapRatio", 0.20);
        ReflectionTestUtils.setField(policy, "yieldToImports", true);
        ReflectionTestUtils.setField(policy, "largeTripleThreshold", 500_000L);
    }

    private DLQueryJob job() {
        DLQueryJob job = mock(DLQueryJob.class);
        when(job.getSlotWeight()).thenReturn(1);
        return job;
    }

    private void heapUsedPercent(int percent) {
        signals.heapUsed = signals.heapMax * percent / 100;
    }

    @Test
    void anEmptyQueueUsesTheConfiguredCeilingWhateverTheHeap() {
        heapUsedPercent(95);
        assertEquals(4, policy.snapshot(List.of()).effectiveMax());
    }

    @Test
    void freeHeapStepsKeepTheirOldThresholds() {
        List<DLQueryJob> running = List.of(job());
        heapUsedPercent(40);
        assertEquals(4, policy.snapshot(running).effectiveMax());
        heapUsedPercent(55);
        assertEquals(2, policy.snapshot(running).effectiveMax());
        heapUsedPercent(70);
        assertEquals(1, policy.snapshot(running).effectiveMax());
        heapUsedPercent(85);
        DLQueryConcurrencyPolicy.ConcurrencySnapshot low = policy.snapshot(running);
        assertEquals(1, low.effectiveMax());
        assertTrue(low.memoryPressure());
        assertEquals(0.15, low.freeHeapRatio(), 0.001);
    }

    @Test
    void anUnknownHeapMaxIsTreatedAsFullyFree() {
        signals.heapMax = 0;
        assertEquals(1.0, policy.snapshot(List.of(job())).freeHeapRatio(), 1e-9);
    }

    @Test
    void canStartAnotherStillHonoursTheSlotBudget() {
        heapUsedPercent(10);
        assertTrue(policy.canStartAnother(List.of(job()), job()));
        assertFalse(policy.canStartAnother(List.of(job(), job(), job(), job()), job()));
    }
}
