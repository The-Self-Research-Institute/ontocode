package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DisjointAndMoveHistoryTest {

    private static final String DISJOINT = "http://www.w3.org/2002/07/owl#disjointWith";
    private static final String SUBCLASS = "http://www.w3.org/2000/01/rdf-schema#subClassOf";

    private Map<String, Object> record(MutationOp op) {
        OntologyHistoryService service = new OntologyHistoryService(mock(SparqlDatasetService.class));
        HistorySyncService sync = mock(HistorySyncService.class);
        ReflectionTestUtils.setField(service, "historySyncService", sync);
        ReflectionTestUtils.setField(service, "self", mock(OntologyHistoryService.class));
        service.recordGroupedMutations("proj", "u@x.com", "u@x.com", List.of(op), true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(sync).syncChange(eq("proj"), anyString(), data.capture());
        return data.getValue();
    }

    private static MutationOp op(String type, String iri, String value, String target) {
        return new MutationOp(type, iri, null, null, null, value, target, null, null, null, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> subs(Map<String, Object> row) {
        return (List<Map<String, String>>) row.get("subChanges");
    }

    @Test
    void addingADisjointClassIsARecordedStatementWithTheTarget() {
        Map<String, Object> row = record(op("addDisjointWith", "http://x/Test1", null, "http://x/test4"));
        assertEquals(1, subs(row).size());
        assertEquals(DISJOINT, subs(row).get(0).get("predicate"));
        assertEquals("http://x/test4", subs(row).get(0).get("newValue"));
        assertEquals("true", subs(row).get(0).get("addition"));
        assertEquals("addStatement", row.get("operationType"));
    }

    @Test
    void removingADisjointClassKeepsTheOldValue() {
        Map<String, Object> row = record(op("deleteDisjointWith", "http://x/Test1", null, "http://x/test4"));
        assertEquals("http://x/test4", subs(row).get(0).get("oldValue"));
        assertEquals("false", subs(row).get(0).get("addition"));
        assertEquals("removeStatement", row.get("operationType"));
    }

    @Test
    void changingAParentRecordsTheOldAndTheNewParent() {
        Map<String, Object> row = record(op("updateSubClassOf", "http://x/Test3", "http://x/old", "http://x/new"));
        assertEquals(SUBCLASS, subs(row).get(0).get("predicate"));
        assertEquals("http://x/old", subs(row).get(0).get("oldValue"));
        assertEquals("http://x/new", subs(row).get(0).get("newValue"));
        assertEquals("updateSubClassOf", row.get("operationType"));
    }

    @Test
    void changingADisjointClassRecordsTheOldAndTheNewClass() {
        Map<String, Object> row = record(op("updateDisjointWith", "http://x/Test1", "http://x/old", "http://x/new"));
        assertEquals(DISJOINT, subs(row).get(0).get("predicate"));
        assertEquals("http://x/old", subs(row).get(0).get("oldValue"));
        assertEquals("http://x/new", subs(row).get(0).get("newValue"));
        assertEquals("updateStatement", row.get("operationType"));
    }

    @Test
    void movingAClassIsOneEditedRowNotACreate() {
        OntologyHistoryService service = new OntologyHistoryService(mock(SparqlDatasetService.class));
        HistorySyncService sync = mock(HistorySyncService.class);
        ReflectionTestUtils.setField(service, "historySyncService", sync);
        ReflectionTestUtils.setField(service, "self", mock(OntologyHistoryService.class));
        service.recordGroupedMutations("proj", "u@x.com", "u@x.com", List.of(
                op("deleteSubClassOf", "http://x/Test3", null, "http://www.w3.org/2002/07/owl#Thing"),
                op("addSubClassOf", "http://x/Test3", null, "http://x/test4")), true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(sync).syncChange(eq("proj"), anyString(), data.capture());
        assertEquals("updateSubClassOf", data.getValue().get("operationType"));
        assertEquals(2, subs(data.getValue()).size());
    }

    @Test
    void classificationUsesTheTargetAsTheNewValueOfAnUpdate() {
        MutationOp update = op("updateSubClassOf", "http://x/A", "http://x/old", "http://x/new");
        assertEquals("http://x/new", HistoryClassification.newValue(update));
        assertEquals("http://x/old", HistoryClassification.oldValue(update));
    }
}
