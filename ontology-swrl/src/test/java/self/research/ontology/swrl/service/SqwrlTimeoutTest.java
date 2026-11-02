package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
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
        ReflectionTestUtils.setField(harness.service, "fetchAllowanceSeconds", 0);
        long[] waited = new long[1];
        ExecutionGate slow = new ExecutionGate(2, 10, 1, 1, 10000) {
            @Override
            public <T> T call(String owner, java.util.concurrent.Callable<T> task, long timeoutSeconds)
                    throws java.util.concurrent.TimeoutException, InterruptedException, java.util.concurrent.ExecutionException {
                long begun = System.currentTimeMillis();
                try {
                    return super.call(owner, () -> {
                        Thread.sleep(5000);
                        return task.call();
                    }, timeoutSeconds);
                } finally {
                    waited[0] = System.currentTimeMillis() - begun;
                }
            }
        };
        ReflectionTestUtils.setField(harness.service, "executionGate", slow);

        SwrlEngineService.SqwrlQueryResult result = harness.service.executeSqwrlQuery(TriageHarness.PROJECT, QUERY, "slow", null);

        assertFalse(result.isSuccess());
        assertEquals("Query timed out after 1 seconds", result.getErrorMessage());
        assertTrue(waited[0] < 4000, "waited " + waited[0]);
    }

    @Test
    void aQuickSqwrlQueryStillReturnsRows() throws Exception {
        TriageHarness harness = new TriageHarness();

        SwrlEngineService.SqwrlQueryResult result = harness.service.executeSqwrlQuery(TriageHarness.PROJECT, QUERY, "ages", null);

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertTrue(result.getRowCount() >= 8);
    }
}
