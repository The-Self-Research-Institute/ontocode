package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqwrlCountExampleSingleTotalTest {

    @Test
    void countExampleReturnsOneRowWithTheTotal() throws Exception {
        TriageHarness harness = new TriageHarness();
        SwrlEngineService.SqwrlQueryResult listed = harness.service.executeSqwrlQuery(
                TriageHarness.PROJECT, "Patient(?p) -> sqwrl:select(?p)", "q", null);
        assertTrue(listed.isSuccess(), String.valueOf(listed.getErrorMessage()));
        int patients = listed.getRowCount();
        assertTrue(patients > 1);

        SwrlEngineService.SqwrlQueryResult counted = harness.service.executeSqwrlQuery(
                TriageHarness.PROJECT, "Patient(?p) -> sqwrl:count(?p)", "q", null);

        assertTrue(counted.isSuccess(), String.valueOf(counted.getErrorMessage()));
        assertEquals(1, counted.getRowCount());
        assertEquals(String.valueOf(patients), counted.getRows().get(0).values().iterator().next());
    }
}
