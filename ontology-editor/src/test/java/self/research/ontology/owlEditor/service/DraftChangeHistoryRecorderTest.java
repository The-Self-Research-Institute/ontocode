package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.OntologyChange;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DraftChangeHistoryRecorderTest {

    private static final String PIZZA = "http://example.org/Pizza";
    private static final String SAUCE = "http://example.org/Sauce";

    private final ChangeTrackingService changeTracking = mock(ChangeTrackingService.class);
    private final OntologyHistoryService history = mock(OntologyHistoryService.class);
    private final DraftChangeHistoryRecorder recorder = new DraftChangeHistoryRecorder(changeTracking, history);

    private static DraftChange draft(String id, String operationType, Map<String, Object> data) {
        DraftChange draft = new DraftChange("p1", "u1", "Uma", operationType, data);
        draft.setId(id);
        return draft;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<Map<String, String>>> subChangesCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    @Test
    void eachDraftIsTrackedAndEachEntityGetsOneBundledHistoryEntryLedByItsCreate() {
        DraftChange note = draft("d1", "addAnnotation",
                Map.of("iri", PIZZA, "property", "http://example.org/note", "value", "thin crust"));
        DraftChange create = draft("d2", "createClass", Map.of("iri", PIZZA, "label", "Pizza"));
        DraftChange sauce = draft("d3", "createClass", Map.of("iri", SAUCE, "label", "Sauce"));

        recorder.record("p1", List.of(note, create, sauce));

        verify(changeTracking, times(3)).recordChange(any(OntologyChange.class));
        ArgumentCaptor<List<Map<String, String>>> subChanges = subChangesCaptor();
        verify(history).recordEdit(eq("p1"), eq("u1"), eq("Uma"), eq("createClass"), eq(PIZZA), eq("Pizza"),
                isNull(), isNull(), eq("Created class: Pizza (1 additional change)"), isNull(),
                subChanges.capture(), eq(false));
        Map<String, String> sub = subChanges.getValue().get(0);
        assertEquals("http://example.org/note", sub.get("predicate"));
        assertEquals("thin crust", sub.get("newValue"));
        assertEquals("http://example.org/note", sub.get("annotationProperty"));
        verify(history).recordEdit(eq("p1"), eq("u1"), eq("Uma"), eq("createClass"), eq(SAUCE), eq("Sauce"),
                isNull(), isNull(), eq("Created class: Sauce"), isNull(), eq(List.of()), eq(false));
    }

    @Test
    void anIriValuedPropertyIsNotMarkedAsAnAnnotationAndRemovalsKeepTheOldValue() {
        DraftChange create = draft("d1", "createClass", Map.of("iri", PIZZA, "label", "Pizza"));
        DraftChange link = draft("d2", "deleteObjectPropertyAssertion",
                Map.of("iri", PIZZA, "property", "http://example.org/hasTopping", "value", "http://example.org/Cheese"));
        DraftChange parent = draft("d3", "deleteSubClassOf", Map.of("iri", PIZZA, "parent", "http://example.org/Food"));

        recorder.record("p1", List.of(create, link, parent));

        ArgumentCaptor<List<Map<String, String>>> subChanges = subChangesCaptor();
        verify(history).recordEdit(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyString(), any(), subChanges.capture(), anyBoolean());
        Map<String, String> objectLink = subChanges.getValue().get(0);
        assertFalse(objectLink.containsKey("annotationProperty"));
        assertEquals("false", objectLink.get("addition"));
        assertEquals("http://example.org/Cheese", objectLink.get("oldValue"));
        Map<String, String> subClass = subChanges.getValue().get(1);
        assertEquals("http://www.w3.org/2000/01/rdf-schema#subClassOf", subClass.get("predicate"));
        assertEquals("http://example.org/Food", subClass.get("oldValue"));
        assertNull(subClass.get("newValue"));
    }

    @Test
    void draftsWithoutAnEntityAreEachRecordedOnTheirOwn() {
        DraftChange first = draft("d1", "addAxiom", Map.of("value", "A SubClassOf B"));
        DraftChange second = draft("d2", "addAxiom", Map.of("value", "C SubClassOf D"));

        recorder.record("p1", List.of(first, second));

        verify(history, times(2)).recordEdit(anyString(), anyString(), anyString(), eq("addAxiom"), isNull(), isNull(),
                isNull(), anyString(), eq("Modified: null"), isNull(), eq(List.of()), eq(false));
    }

    @Test
    void aChangeTrackingFailureNeverFailsThePublish() {
        doThrow(new RuntimeException("mongo down")).when(changeTracking).recordChange(any(OntologyChange.class));

        assertDoesNotThrow(() -> recorder.record("p1",
                List.of(draft("d1", "createClass", Map.of("iri", PIZZA, "label", "Pizza")))));
        verify(history, times(0)).recordEdit(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), any(), anyList(), anyBoolean());
    }

    @Test
    void markPublishedPassesTheProjectAndUserToTheHistoryService() {
        recorder.markPublished("p1", "u1");

        verify(history).markDraftHistoryPublished("p1", "u1");
    }

    @Test
    void markPublishedNeverFailsAPublishThatAlreadyWentThrough() {
        doThrow(new RuntimeException("mongo down")).when(history).markDraftHistoryPublished(anyString(), anyString());

        assertDoesNotThrow(() -> recorder.markPublished("p1", "u1"));
    }
}
