package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GroupedMutationChangeSetTest {

    @Test
    void everyEntryOfOneMutationRequestSharesOneChangeSet() {
        OntologyHistoryService service = new OntologyHistoryService(mock(SparqlDatasetService.class));
        HistorySyncService sync = mock(HistorySyncService.class);
        ReflectionTestUtils.setField(service, "historySyncService", sync);
        ReflectionTestUtils.setField(service, "self", mock(OntologyHistoryService.class));

        MutationOp first = MutationOp.forSubClassOfChange("addSubClassOf", "http://x/A", "A", "http://x/P", true);
        MutationOp second = MutationOp.forSubClassOfChange("addSubClassOf", "http://x/B", "B", "http://x/P", true);

        service.recordGroupedMutations("proj", "u@x.com", "u@x.com", List.of(first, second), false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(sync, atLeast(2)).syncChange(eq("proj"), anyString(), data.capture());
        List<Map<String, Object>> all = data.getAllValues();
        assertEquals(2, all.size());
        Object id = all.get(0).get("changeSetId");
        assertNotNull(id);
        assertEquals(id, all.get(1).get("changeSetId"));
        assertEquals(ChangeOrigin.MANUAL, all.get(0).get("source"));
    }

    @Test
    void separateRequestsGetSeparateChangeSets() {
        OntologyHistoryService service = new OntologyHistoryService(mock(SparqlDatasetService.class));
        HistorySyncService sync = mock(HistorySyncService.class);
        ReflectionTestUtils.setField(service, "historySyncService", sync);
        ReflectionTestUtils.setField(service, "self", mock(OntologyHistoryService.class));
        MutationOp op = MutationOp.forSubClassOfChange("addSubClassOf", "http://x/A", "A", "http://x/P", true);

        service.recordGroupedMutations("proj", "u@x.com", "u@x.com", List.of(op), false);
        service.recordGroupedMutations("proj", "u@x.com", "u@x.com", List.of(op), false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(sync, atLeast(2)).syncChange(eq("proj"), anyString(), data.capture());
        assertEquals(2, data.getAllValues().size());
        assertNotEquals(data.getAllValues().get(0).get("changeSetId"), data.getAllValues().get(1).get("changeSetId"));
    }

    @Test
    void characteristicChangesAreRecordedAsTypeSubChanges() {
        OntologyHistoryService service = new OntologyHistoryService(mock(SparqlDatasetService.class));
        HistorySyncService sync = mock(HistorySyncService.class);
        ReflectionTestUtils.setField(service, "historySyncService", sync);
        ReflectionTestUtils.setField(service, "self", mock(OntologyHistoryService.class));
        MutationOp op = new MutationOp("addCharacteristic", "http://x/p", "p", null, null, null,
                "http://www.w3.org/2002/07/owl#FunctionalProperty", null, null, null, null, null, null, null, null);

        service.recordGroupedMutations("proj", "u@x.com", "u@x.com", List.of(op), false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(sync).syncChange(eq("proj"), anyString(), data.capture());
        @SuppressWarnings("unchecked")
        List<Map<String, String>> subs = (List<Map<String, String>>) data.getValue().get("subChanges");
        assertEquals(1, subs.size());
        assertEquals("http://www.w3.org/1999/02/22-rdf-syntax-ns#type", subs.get(0).get("predicate"));
        assertEquals("http://www.w3.org/2002/07/owl#FunctionalProperty", subs.get(0).get("newValue"));
        assertEquals("true", subs.get(0).get("addition"));
    }
}
