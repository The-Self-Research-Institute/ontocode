package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqwrlRowCapTest {

    private static final String QUERY = "Patient(?p) ^ hasAge(?p, ?a) -> sqwrl:select(?p, ?a)";

    private static SwrlEngineService.SqwrlQueryResult run(TriageHarness harness, Integer maxResults) {
        return harness.service.executeSqwrlQuery(TriageHarness.PROJECT, QUERY, "q", maxResults);
    }

    private static int totalOf(TriageHarness harness) {
        SwrlEngineService.SqwrlQueryResult all = run(harness, null);
        assertTrue(all.isSuccess(), String.valueOf(all.getErrorMessage()));
        return all.getTotalRows();
    }

    @Test
    void legacyShapeIsIntactWhenUnderTheCap() throws Exception {
        TriageHarness harness = new TriageHarness();
        SwrlEngineService.SqwrlQueryResult result = run(harness, null);

        assertTrue(result.isSuccess());
        assertEquals(1000, result.getRowLimit());
        assertEquals(result.getRows().size(), result.getRowCount());
        assertEquals(result.getRowCount(), result.getTotalRows());
        assertTrue(result.isTotalRowsExact());
        assertFalse(result.isTruncated());
        assertFalse(result.isBytesCapped());
        assertTrue(result.getRowCount() >= 8);
        assertEquals(2, result.getColumnNames().size());
    }

    @Test
    void theHardCapStopsStoringButTheCountContinues() throws Exception {
        TriageHarness harness = new TriageHarness();
        int total = totalOf(harness);
        ReflectionTestUtils.setField(harness.service, "sqwrlMaxRows", 3);

        SwrlEngineService.SqwrlQueryResult result = run(harness, null);

        assertEquals(3, result.getRowCount());
        assertEquals(3, result.getRows().size());
        assertEquals(3, result.getRowLimit());
        assertEquals(total, result.getTotalRows());
        assertTrue(result.isTotalRowsExact());
        assertTrue(result.isTruncated());
    }

    @Test
    void maxResultsIsHonouredBelowTheCap() throws Exception {
        TriageHarness harness = new TriageHarness();
        int total = totalOf(harness);

        SwrlEngineService.SqwrlQueryResult result = run(harness, 4);

        assertEquals(4, result.getRowCount());
        assertEquals(4, result.getRowLimit());
        assertEquals(total, result.getTotalRows());
        assertTrue(result.isTruncated());
    }

    @Test
    void maxResultsAboveTheCapIsClamped() throws Exception {
        TriageHarness harness = new TriageHarness();
        ReflectionTestUtils.setField(harness.service, "sqwrlMaxRows", 2);

        SwrlEngineService.SqwrlQueryResult result = run(harness, 5000);

        assertEquals(2, result.getRowCount());
        assertEquals(2, result.getRowLimit());
        assertTrue(result.isTruncated());
    }

    @Test
    void countingStopsAtTheCountLimitAndSaysSo() throws Exception {
        TriageHarness harness = new TriageHarness();
        int total = totalOf(harness);
        assertTrue(total > 5);
        ReflectionTestUtils.setField(harness.service, "sqwrlMaxRows", 2);
        ReflectionTestUtils.setField(harness.service, "sqwrlCountLimit", 5);

        SwrlEngineService.SqwrlQueryResult result = run(harness, null);

        assertEquals(2, result.getRowCount());
        assertEquals(5, result.getTotalRows());
        assertFalse(result.isTotalRowsExact());
        assertTrue(result.isTruncated());
    }

    @Test
    void theByteCapFlagsAndStillCounts() throws Exception {
        TriageHarness harness = new TriageHarness();
        int total = totalOf(harness);
        ReflectionTestUtils.setField(harness.service, "sqwrlMaxBytes", 12L);

        SwrlEngineService.SqwrlQueryResult result = run(harness, null);

        assertTrue(result.isBytesCapped());
        assertTrue(result.isTruncated());
        assertTrue(result.getRowCount() < total);
        assertEquals(total, result.getTotalRows());
        assertTrue(result.isTotalRowsExact());
    }

    @Test
    void aFailedQueryCarriesNoCapFlags() throws Exception {
        TriageHarness harness = new TriageHarness();

        SwrlEngineService.SqwrlQueryResult result = harness.service.executeSqwrlQuery(TriageHarness.PROJECT, "not a query", "bad", null);

        assertFalse(result.isSuccess());
        assertFalse(result.isTruncated());
        assertEquals(0, result.getRowCount());
    }
}
