package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqwrlTimeoutTest {

    private static final String QUERY = "Patient(?p) ^ hasAge(?p, ?a) -> sqwrl:select(?p, ?a)";

    @Test
    void aSqwrlQueryThatRunsPastTheLimitFailsWithAClearMessage() throws Exception {
        TriageHarness harness = new TriageHarness();
        ReflectionTestUtils.setField(harness.service, "executionTimeoutSeconds", 1);
        java.util.concurrent.ExecutorService real = java.util.concurrent.Executors.newCachedThreadPool();
        java.util.concurrent.ExecutorService slow = Mockito.mock(java.util.concurrent.ExecutorService.class);
        Mockito.when(slow.submit(Mockito.<java.util.concurrent.Callable<Object>>any())).thenAnswer(invocation -> {
            java.util.concurrent.Callable<Object> work = invocation.getArgument(0);
            return real.submit(() -> {
                Thread.sleep(5000);
                return work.call();
            });
        });
        ReflectionTestUtils.setField(harness.service, "executorService", slow);

        long start = System.currentTimeMillis();
        SwrlEngineService.SqwrlQueryResult result = harness.service.executeSqwrlQuery(TriageHarness.PROJECT, QUERY, "slow", null);

        assertFalse(result.isSuccess());
        assertEquals("Query timed out after 1 seconds", result.getErrorMessage());
        assertTrue(System.currentTimeMillis() - start < 4000);
    }

    @Test
    void aQuickSqwrlQueryStillReturnsRows() throws Exception {
        TriageHarness harness = new TriageHarness();

        SwrlEngineService.SqwrlQueryResult result = harness.service.executeSqwrlQuery(TriageHarness.PROJECT, QUERY, "ages", null);

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertTrue(result.getRowCount() >= 8);
    }
}
