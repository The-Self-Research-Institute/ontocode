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
        Mockito.when(harness.repository.findByProjectIdAndEnabled(TriageHarness.PROJECT, true)).thenAnswer(invocation -> {
            Thread.sleep(5000);
            return java.util.List.of();
        });

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
