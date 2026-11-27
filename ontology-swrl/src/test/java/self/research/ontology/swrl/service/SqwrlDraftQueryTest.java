package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqwrlDraftQueryTest {

    private static final String QUERY = "Patient(?p) ^ hasAge(?p, ?a) -> sqwrl:select(?p, ?a)";

    @Test
    void aDraftQueryReadsTheDraftAndNeverThePublicOntology() throws Exception {
        TriageHarness harness = new TriageHarness();
        Mockito.when(harness.client.fetchOntology(TriageHarness.PROJECT)).thenAnswer(invocation -> TriageHarness.bare());

        SqwrlQueryResult result = harness.service.executeSqwrlQuery(TriageHarness.PROJECT, QUERY, "q", null, "owner");

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        assertFalse(result.getRows().isEmpty());
        Mockito.verify(harness.client).fetchOntologyWithStamp(TriageHarness.PROJECT, "owner");
        Mockito.verify(harness.client, Mockito.never()).fetchOntology(TriageHarness.PROJECT);
    }

    @Test
    void aPublicQueryStillReadsThePublicOntology() throws Exception {
        TriageHarness harness = new TriageHarness();

        SqwrlQueryResult result = harness.service.executeSqwrlQuery(TriageHarness.PROJECT, QUERY, "q", null);

        assertTrue(result.isSuccess(), String.valueOf(result.getErrorMessage()));
        Mockito.verify(harness.client).fetchOntology(TriageHarness.PROJECT);
        Mockito.verify(harness.client, Mockito.never()).fetchOntologyWithStamp(Mockito.anyString(), Mockito.any());
    }
}
