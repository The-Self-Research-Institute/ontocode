package self.research.ontology.reasoner.service;

import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.reasoner.model.ReasoningJob;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class QueueTestSupport {

    private QueueTestSupport() {}

    static EditorClient editorClient() {
        EditorClient editorClient = mock(EditorClient.class);
        when(editorClient.getTripleCount(any())).thenReturn(10L);
        return editorClient;
    }

    static ReasoningConcurrencyPolicy policy(EditorClient editorClient) {
        ReasoningConcurrencyPolicy policy = new ReasoningConcurrencyPolicy(editorClient);
        ReflectionTestUtils.setField(policy, "configuredMax", 2);
        ReflectionTestUtils.setField(policy, "memoryAware", false);
        ReflectionTestUtils.setField(policy, "minFreeHeapRatio", 0.20);
        ReflectionTestUtils.setField(policy, "largeTripleThreshold", 500_000L);
        ReflectionTestUtils.setField(policy, "slotBudget", 2);
        ReflectionTestUtils.setField(policy, "yieldToImports", true);
        ReflectionTestUtils.setField(policy, "fastLaneMaxConcurrent", 3);
        ReflectionTestUtils.setField(policy, "heavyLaneMaxConcurrent", 1);
        return policy;
    }

    static ReasoningResultRetention retention() {
        ReasoningResultRetention retention = new ReasoningResultRetention();
        ReflectionTestUtils.setField(retention, "heapComfortUsedRatio", 1.0);
        ReflectionTestUtils.setField(retention, "heapPressureUsedRatio", 1.0);
        ReflectionTestUtils.setField(retention, "maxRetainedResults", 50);
        ReflectionTestUtils.setField(retention, "idleEvictionMinutes", 15);
        return retention;
    }

    static ReasoningQueueManager queueManager() {
        return queueManager(editorClient());
    }

    static ReasoningQueueManager queueManager(EditorClient editorClient) {
        return new ReasoningQueueManager(editorClient, policy(editorClient), retention(), ReasoningJobStore.disabled());
    }

    static ReasoningJob enqueue(ReasoningQueueManager manager, String projectId) {
        return manager.enqueue(ReasoningJob.JobType.REASONER_CLASSIFY, projectId, null, null, "HERMIT", null);
    }
}
