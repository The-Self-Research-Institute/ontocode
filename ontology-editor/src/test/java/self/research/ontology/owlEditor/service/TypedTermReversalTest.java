package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TypedTermReversalTest {

    private static final String LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    private static final String ENTITY = "http://x/A";

    private static HistoryChange changeWith(HistoryChange.SubChange sub) {
        HistoryChange change = new HistoryChange();
        change.setId("c1");
        change.setProjectId("p");
        change.setEntityIRI(ENTITY);
        change.setOperationType("addAnnotation");
        change.setSubChanges(List.of(sub));
        return change;
    }

    private static HistoryChange.SubChange label(String value, String language, String datatype) {
        HistoryChange.SubChange sub = new HistoryChange.SubChange(LABEL, null, value, LABEL, true);
        sub.setId("s1");
        sub.setLanguage(language);
        sub.setDatatype(datatype);
        return sub;
    }

    @Test
    void undoingAnAddedLabelDeletesTheExactLanguageTaggedValue() {
        HistoryChange.SubChange sub = label("Pizza", "fr", null);
        RollbackMutationPlanner.Plan plan = new RollbackMutationPlanner().planUndo(changeWith(sub), List.of(sub));
        MutationOp op = plan.mutations().get(0);
        assertEquals("deleteAnnotation", op.type());
        assertEquals("fr", op.language());
        assertEquals("Pizza", op.value());
    }

    @Test
    void undoingATypedLiteralKeepsItsDatatype() {
        HistoryChange.SubChange sub = label("42", null, "http://www.w3.org/2001/XMLSchema#integer");
        RollbackMutationPlanner.Plan plan = new RollbackMutationPlanner().planUndo(changeWith(sub), List.of(sub));
        assertEquals("http://www.w3.org/2001/XMLSchema#integer", plan.mutations().get(0).datatype());
    }

    @Test
    void entriesWithoutRecordedTypesStillUseThePlainValueMatch() {
        HistoryChange.SubChange sub = label("Pizza", null, null);
        RollbackMutationPlanner.Plan plan = new RollbackMutationPlanner().planUndo(changeWith(sub), List.of(sub));
        assertNull(plan.mutations().get(0).language());
        assertNull(plan.mutations().get(0).datatype());
    }

    @Test
    void recordedAnnotationOpsCarryTheirLanguageIntoTheSubChange() {
        OntologyHistoryService service = new OntologyHistoryService(mock(SparqlDatasetService.class));
        HistorySyncService sync = mock(HistorySyncService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "historySyncService", sync);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", mock(OntologyHistoryService.class));
        MutationOp op = new MutationOp("addAnnotation", ENTITY, null, null, LABEL, "Pizza", null, null, null, null, null,
                null, "fr", null, null);

        service.recordGroupedMutations("p", "u", "u", List.of(op), false);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> data = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(sync).syncChange(eq("p"), anyString(), data.capture());
        @SuppressWarnings("unchecked")
        List<Map<String, String>> subs = (List<Map<String, String>>) data.getValue().get("subChanges");
        assertEquals("fr", subs.get(0).get("language"));
    }
}
