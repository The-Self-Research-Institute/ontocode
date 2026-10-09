package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.repository.DraftChangeRepository;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DraftTrackingPublishHistoryTest {

    @Mock
    private DraftChangeRepository draftRepository;
    @Mock
    private OntologyMutationService mutationService;
    @Mock
    private SparqlDatasetService datasetService;
    @Mock
    private OntologyIndexService indexService;
    @Mock
    private ProjectMetadataService metadataService;
    @Mock
    private DraftChangeHistoryRecorder draftChangeHistory;
    @Mock
    private self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService collaborativeEditService;
    @Mock
    private DraftPublishService draftPublishService;
    @Mock
    private MainGraphRevisionService mainGraphRevisionService;
    @Mock
    private DraftPublishMergeService draftPublishMergeService;
    @Mock
    private DraftCopyService draftCopyService;

    private DraftTrackingService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new DraftTrackingService(draftRepository, mutationService, datasetService, indexService,
                metadataService, draftChangeHistory, Runnable::run, collaborativeEditService, draftPublishService,
                mainGraphRevisionService, draftPublishMergeService, draftCopyService);
        when(draftCopyService.isReady("p1", "u1")).thenReturn(true);
        when(draftCopyService.getMainRevisionAtCopy("p1", "u1")).thenReturn(5L);
        when(mainGraphRevisionService.getRevision("p1")).thenReturn(5L);
    }

    @Test
    void publishingByMovingTheGraphClearsTheDraftFlagOnThatUsersHistory() {
        var result = service.applyDrafts("p1", "u1", false, false);

        assertTrue(result.isSuccess());
        verify(datasetService).moveDraftToMain("p1", "u1");
        verify(draftChangeHistory).markPublished("p1", "u1");
    }

    @Test
    void theFlagIsClearedEvenWhenAFollowUpStepAfterTheLivePublishFails() {
        doThrow(new RuntimeException("revision store down")).when(mainGraphRevisionService).incrementRevision("p1");

        service.applyDrafts("p1", "u1", false, false);

        verify(draftChangeHistory).markPublished("p1", "u1");
    }

    @Test
    void aPublishThatNeverReachedTheGraphLeavesTheDraftFlagAlone() throws Exception {
        doThrow(new RuntimeException("fuseki down")).when(datasetService).moveDraftToMain("p1", "u1");

        service.applyDrafts("p1", "u1", false, false);

        verify(draftChangeHistory, never()).markPublished("p1", "u1");
    }

    @Test
    void publishingWithAThreeWayMergeClearsTheDraftFlagToo() throws Exception {
        DraftPublishAnalysis analysis = org.mockito.Mockito.mock(DraftPublishAnalysis.class);
        when(draftPublishService.analyze(any(), any(), anyList(), anyBoolean())).thenReturn(analysis);
        when(analysis.isBlocked(anyBoolean())).thenReturn(false);

        service.applyDrafts("p1", "u1", false, true, Map.of());

        verify(draftPublishMergeService).publishWithThreeWayMerge(any(), any(), any(), any());
        verify(draftChangeHistory).markPublished("p1", "u1");
    }
}
