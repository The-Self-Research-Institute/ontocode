package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.OntologyChange;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DraftChangeHistoryRecorderImportTest {

    private final ChangeTrackingService changeTracking = mock(ChangeTrackingService.class);
    private final OntologyHistoryService history = mock(OntologyHistoryService.class);
    private final DraftChangeHistoryRecorder recorder = new DraftChangeHistoryRecorder(changeTracking, history);

    private static DraftChange draft(String id, String session, String operationType, Map<String, Object> data) {
        DraftChange draft = new DraftChange("p1", "u1", "Uma", operationType, data);
        draft.setId(id);
        draft.setSessionId(session);
        return draft;
    }

    @Test
    void importDraftsAreLeftOutWhileOtherDraftsAreRecordedAsBefore() {
        String importSession = "spreadsheet-import-123";
        DraftChange s001 = draft("d1", importSession, "createIndividual", Map.of("iri", "http://ex.org/S001"));
        DraftChange s001Value = draft("d2", importSession, "addDataPropertyAssertion",
                Map.of("iri", "http://ex.org/S001", "property", "http://ex.org/hasValue", "value", "23.7"));
        DraftChange s002 = draft("d3", importSession, "createIndividual", Map.of("iri", "http://ex.org/S002"));
        DraftChange handEdit = draft("d4", "session_1", "createClass", Map.of("iri", "http://ex.org/Pizza", "label", "Pizza"));

        recorder.record("p1", List.of(s001, s001Value, s002, handEdit));

        verify(changeTracking, times(1)).recordChange(any(OntologyChange.class));
        verify(history, times(1)).recordEdit(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyString(), any(), anyList(), anyBoolean());
        verify(history).recordEdit(eq("p1"), eq("u1"), eq("Uma"), eq("createClass"), eq("http://ex.org/Pizza"), eq("Pizza"),
                any(), any(), anyString(), any(), anyList(), eq(false));
    }

    @Test
    void aDraftWithOnlyAnImportRecordsNothingExtra() {
        String importSession = "spreadsheet-import-123";
        recorder.record("p1", List.of(
                draft("d1", importSession, "createIndividual", Map.of("iri", "http://ex.org/S001")),
                draft("d2", importSession, "createIndividual", Map.of("iri", "http://ex.org/S002"))));

        verify(changeTracking, never()).recordChange(any(OntologyChange.class));
        verify(history, never()).recordEdit(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), anyString(), any(), anyList(), anyBoolean());
    }
}
