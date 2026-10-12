package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CodeViewHistoryRecorderPrefixTest {

    @Test
    void aSinglePrefixAdditionIsRecordedWithItsPreciseOpType() {
        OntologyHistoryService history = mock(OntologyHistoryService.class);
        CodeViewHistoryRecorder recorder = new CodeViewHistoryRecorder(history, mock(DraftTrackingService.class));

        Model oldModel = new LinkedHashModel();
        Model newModel = new LinkedHashModel();
        newModel.setNamespace("ex", "http://example.org/");

        recorder.record("p", "u@x.com", "u@x.com", oldModel, newModel, false);

        verify(history).recordEdit(eq("p"), eq("u@x.com"), eq("u@x.com"), eq("prefixAdded"),
                isNull(), eq("ex"), isNull(), eq("http://example.org/"), anyString(),
                isNull(), isNull(), anyBoolean(), any());
    }

    @Test
    void severalPrefixChangesInOneSaveAreGroupedIntoASingleEntry() {
        OntologyHistoryService history = mock(OntologyHistoryService.class);
        CodeViewHistoryRecorder recorder = new CodeViewHistoryRecorder(history, mock(DraftTrackingService.class));

        Model oldModel = new LinkedHashModel();
        oldModel.setNamespace("foaf", "http://xmlns.com/foaf/0.1/");
        oldModel.setNamespace("old", "http://old.org/");

        Model newModel = new LinkedHashModel();
        newModel.setNamespace("foaf", "http://xmlns.com/foaf/0.2/"); // modified
        newModel.setNamespace("ex", "http://example.org/"); // added
        // "old" removed

        recorder.record("p", "u@x.com", "u@x.com", oldModel, newModel, false);

        verify(history).recordEdit(eq("p"), eq("u@x.com"), eq("u@x.com"), eq("prefixesChanged"),
                isNull(), eq("3 prefixes"), isNull(), isNull(), anyString(),
                isNull(), isNull(), anyBoolean(), any());
        verify(history, never()).recordEdit(anyString(), anyString(), anyString(), eq("prefixAdded"),
                any(), any(), any(), any(), anyString(), any(), any(), anyBoolean(), any());
        verify(history, never()).recordEdit(anyString(), anyString(), anyString(), eq("prefixModified"),
                any(), any(), any(), any(), anyString(), any(), any(), anyBoolean(), any());
        verify(history, never()).recordEdit(anyString(), anyString(), anyString(), eq("prefixDeleted"),
                any(), any(), any(), any(), anyString(), any(), any(), anyBoolean(), any());
    }

    @Test
    void noPrefixChangeRecordsNothing() {
        OntologyHistoryService history = mock(OntologyHistoryService.class);
        CodeViewHistoryRecorder recorder = new CodeViewHistoryRecorder(history, mock(DraftTrackingService.class));

        Model oldModel = new LinkedHashModel();
        oldModel.setNamespace("ex", "http://example.org/");
        Model newModel = new LinkedHashModel();
        newModel.setNamespace("ex", "http://example.org/");

        recorder.record("p", "u@x.com", "u@x.com", oldModel, newModel, false);

        verify(history, never()).recordEdit(anyString(), anyString(), anyString(), eq("prefixesChanged"),
                any(), any(), any(), any(), anyString(), any(), any(), anyBoolean(), any());
    }

    @Test
    void anOldCopyWithNoPrefixListIsNotReportedAsEveryPrefixBeingAdded() {
        OntologyHistoryService history = mock(OntologyHistoryService.class);
        CodeViewHistoryRecorder recorder = new CodeViewHistoryRecorder(history, mock(DraftTrackingService.class));

        Model oldModel = new LinkedHashModel();
        Model newModel = new LinkedHashModel();
        newModel.setNamespace("ex", "http://example.org/");
        newModel.setNamespace("owl", "http://www.w3.org/2002/07/owl#");

        recorder.record("p", "u@x.com", "u@x.com", oldModel, newModel, true, null, false);

        verify(history, never()).recordEdit(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), anyString(), any(), any(), anyBoolean(), any());
    }

    @Test
    void comparingTwoPrefixListsRecordsOnlyTheOneThatWasAdded() {
        OntologyHistoryService history = mock(OntologyHistoryService.class);
        CodeViewHistoryRecorder recorder = new CodeViewHistoryRecorder(history, mock(DraftTrackingService.class));

        recorder.recordPrefixes("p", "u@x.com", "u@x.com",
                java.util.Map.of("owl", "http://www.w3.org/2002/07/owl#"),
                java.util.Map.of("owl", "http://www.w3.org/2002/07/owl#", "ex2", "http://example.org/ex2#"),
                true, null);

        verify(history).recordEdit(eq("p"), eq("u@x.com"), eq("u@x.com"), eq("prefixAdded"),
                isNull(), eq("ex2"), isNull(), eq("http://example.org/ex2#"), anyString(),
                isNull(), isNull(), eq(true), any());
    }
}
