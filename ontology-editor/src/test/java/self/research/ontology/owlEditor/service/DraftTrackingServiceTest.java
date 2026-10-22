package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.repository.DraftChangeRepository;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DraftTrackingServiceTest {

    private final DraftChangeRepository draftRepository = mock(DraftChangeRepository.class);
    private final OntologyMutationService mutationService = mock(OntologyMutationService.class);
    private final SparqlDatasetService datasetService = mock(SparqlDatasetService.class);
    private final OntologyIndexService indexService = mock(OntologyIndexService.class);
    private final ProjectMetadataService metadataService = mock(ProjectMetadataService.class);
    private final DraftChangeHistoryRecorder draftChangeHistory = mock(DraftChangeHistoryRecorder.class);
    private final Executor metadataExecutor = Runnable::run;
    private final CollaborativeEditService collaborativeEditService = mock(CollaborativeEditService.class);
    private final DraftPublishService draftPublishService = mock(DraftPublishService.class);
    private final MainGraphRevisionService mainGraphRevisionService = mock(MainGraphRevisionService.class);
    private final DraftPublishMergeService draftPublishMergeService = mock(DraftPublishMergeService.class);
    private final DraftCopyService draftCopyService = mock(DraftCopyService.class);

    private DraftTrackingService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new DraftTrackingService(draftRepository, mutationService, datasetService, indexService,
                metadataService, draftChangeHistory, metadataExecutor, collaborativeEditService,
                draftPublishService, mainGraphRevisionService, draftPublishMergeService, draftCopyService);
        when(draftCopyService.isReady("p1", "u1")).thenReturn(true);
        when(mainGraphRevisionService.getRevision("p1")).thenReturn(5L);
        when(draftCopyService.getMainRevisionAtCopy("p1", "u1")).thenReturn(5L);
    }

    private static DraftChange draft(String id) {
        DraftChange draft = new DraftChange("p1", "u1", "Uma", "createClass",
                Map.of("iri", "http://example.org/Pizza", "label", "Pizza"));
        draft.setId(id);
        return draft;
    }

    @Test
    void moveGraphPublishMarksDraftsAppliedAndReportsNoFailureWhenRecordingFullySucceeds() {
        List<DraftChange> drafts = List.of(draft("d1"), draft("d2"));
        when(draftRepository.findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc("p1", "u1")).thenReturn(drafts);
        when(draftChangeHistory.record("p1", drafts)).thenReturn(0);

        DraftTrackingService.ApplyDraftsResult result = service.applyDrafts("p1", "u1", false);

        assertTrue(result.isSuccess());
        assertEquals("Published draft successfully", result.getMessage());
        verify(draftRepository).saveAll(drafts);
        assertTrue(drafts.get(0).isApplied());
        assertTrue(drafts.get(1).isApplied());
    }

    @Test
    void moveGraphPublishStillMarksDraftsAppliedWhenRecordingPartiallyFailsButSaysSo() {
        List<DraftChange> drafts = List.of(draft("d1"), draft("d2"));
        when(draftRepository.findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc("p1", "u1")).thenReturn(drafts);
        when(draftChangeHistory.record("p1", drafts)).thenReturn(2);

        DraftTrackingService.ApplyDraftsResult result = service.applyDrafts("p1", "u1", false);

        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("2 changes could not be recorded"));
        verify(draftRepository).saveAll(drafts);
        assertTrue(drafts.get(0).isApplied());
        assertTrue(drafts.get(1).isApplied());
    }

    @Test
    void moveGraphPublishSingularWordingForOneFailure() {
        List<DraftChange> drafts = List.of(draft("d1"));
        when(draftRepository.findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc("p1", "u1")).thenReturn(drafts);
        when(draftChangeHistory.record("p1", drafts)).thenReturn(1);

        DraftTrackingService.ApplyDraftsResult result = service.applyDrafts("p1", "u1", false);

        assertTrue(result.getMessage().contains("1 change could not be recorded"));
        assertTrue(!result.getMessage().contains("1 changes"));
    }

    @Test
    void mergePublishStillMarksDraftsAppliedWhenRecordingPartiallyFailsButSaysSo() {
        List<DraftChange> drafts = List.of(draft("d1"));
        when(draftRepository.findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc("p1", "u1")).thenReturn(drafts);
        DraftPublishAnalysis analysis = mock(DraftPublishAnalysis.class);
        when(analysis.isBlocked(false)).thenReturn(false);
        when(draftPublishService.analyze("p1", "u1", drafts, true)).thenReturn(analysis);
        when(draftChangeHistory.record("p1", drafts)).thenReturn(1);

        DraftTrackingService.ApplyDraftsResult result = service.applyDrafts("p1", "u1", false, true, null);

        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("1 change could not be recorded"));
        verify(draftRepository).saveAll(drafts);
        assertTrue(drafts.get(0).isApplied());
    }

    @Test
    void publishStillSucceedsWithNoMessageSuffixWhenThereAreNoUnappliedDrafts() {
        when(draftRepository.findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc("p1", "u1"))
                .thenReturn(List.of());

        DraftTrackingService.ApplyDraftsResult result = service.applyDrafts("p1", "u1", false);

        assertTrue(result.isSuccess());
        assertEquals("Published draft successfully", result.getMessage());
        verify(draftChangeHistory, org.mockito.Mockito.never()).record(anyString(), anyList());
    }
    @Test
    void publishPromotesDraftDocumentsOnlyAfterTheGraphMove() {
        DraftDocumentSupport documents = mock(DraftDocumentSupport.class);
        service.setDraftDocuments(new DraftDocuments(List.of(documents)));
        List<DraftChange> drafts = List.of(draft("d1"));
        when(draftRepository.findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc("p1", "u1")).thenReturn(drafts);
        when(draftChangeHistory.record("p1", drafts)).thenReturn(0);

        DraftTrackingService.ApplyDraftsResult result = service.applyDrafts("p1", "u1", false);

        assertTrue(result.isSuccess());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(datasetService, documents);
        order.verify(datasetService).moveDraftToMain("p1", "u1");
        order.verify(documents).promote("p1", "u1");
    }

    @Test
    void aFailingDraftDocumentPromotionDoesNotFailThePublish() {
        DraftDocumentSupport documents = mock(DraftDocumentSupport.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(documents).promote("p1", "u1");
        service.setDraftDocuments(new DraftDocuments(List.of(documents)));
        List<DraftChange> drafts = List.of(draft("d1"));
        when(draftRepository.findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc("p1", "u1")).thenReturn(drafts);
        when(draftChangeHistory.record("p1", drafts)).thenReturn(0);

        assertTrue(service.applyDrafts("p1", "u1", false).isSuccess());
    }

    @Test
    void discardRemovesTheUsersDraftDocumentsToo() {
        DraftDocumentSupport documents = mock(DraftDocumentSupport.class);
        service.setDraftDocuments(new DraftDocuments(List.of(documents)));
        when(draftRepository.findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc("p1", "u1")).thenReturn(List.of());

        service.discardDrafts("p1", "u1");

        verify(documents).discard("p1", "u1");
    }
}
