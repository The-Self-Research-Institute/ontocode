package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.repository.DraftChangeRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DraftTrackingFrozenPublishTest {

    private static final String KEY = "pr-snapshot-pr1";

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

    private static DraftChange change(String id) {
        DraftChange change = new DraftChange();
        change.setId(id);
        change.setUserId("author");
        change.setOperationType("createClass");
        change.setOperationData(new java.util.HashMap<>(Map.of("iri", "urn:x#" + id, "label", id)));
        return change;
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new DraftTrackingService(draftRepository, mutationService, datasetService, indexService,
                metadataService, draftChangeHistory, Runnable::run, collaborativeEditService, draftPublishService,
                mainGraphRevisionService, draftPublishMergeService, draftCopyService);
        when(draftRepository.findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc("p1", "author"))
                .thenReturn(List.of(change("c1"), change("c2"), change("c3")));
        when(draftCopyService.isReady("p1", KEY)).thenReturn(true);
        when(draftCopyService.getMainRevisionAtCopy("p1", KEY)).thenReturn(5L);
        when(mainGraphRevisionService.getRevision("p1")).thenReturn(5L);
    }

    private DraftPublishDiff diff(boolean available, boolean publicChanged, Set<String> unresolved) {
        DraftPublishDiff diff = mock(DraftPublishDiff.class);
        when(diff.available()).thenReturn(available);
        when(diff.publicChanged()).thenReturn(publicChanged);
        when(diff.unresolved(anyMap())).thenReturn(unresolved);
        return diff;
    }

    @Test
    @SuppressWarnings("unchecked")
    void whenPublicDidNotMoveTheFrozenGraphIsMovedAndOnlyThePrsOwnRowsAreMarked() throws Exception {
        DraftPublishDiff prepared = diff(true, false, Set.of());
        when(draftPublishMergeService.comparePublish("p1", KEY)).thenReturn(prepared);

        var result = service.applyFrozenPr("p1", "author", KEY, List.of("c1", "c2"), List.of("cs1"), Map.of());

        assertTrue(result.isSuccess());
        assertEquals(2, result.getAppliedCount());
        verify(datasetService).moveDraftToMain("p1", KEY);
        verify(datasetService, never()).moveDraftToMain("p1", "author");
        verify(draftChangeHistory).markChangeSetsPublished("p1", List.of("cs1"));
        verify(draftChangeHistory, never()).markPublished("p1", "author");
        verify(draftPublishService).clearBaseline("p1", KEY);
        verify(draftPublishService, never()).clearBaseline("p1", "author");
        ArgumentCaptor<List<DraftChange>> saved = ArgumentCaptor.forClass(List.class);
        verify(draftRepository).saveAll(saved.capture());
        assertEquals(List.of("c1", "c2"), saved.getValue().stream().map(DraftChange::getId).toList());
        assertTrue(saved.getValue().stream().allMatch(DraftChange::isApplied));
    }

    @Test
    void theAppliedCountIsTheNumberOfChangesTheFrozenCopyCarriesEvenIfTheAuthorUndoneSome() throws Exception {
        DraftPublishDiff prepared = diff(true, false, Set.of());
        when(draftPublishMergeService.comparePublish("p1", KEY)).thenReturn(prepared);

        var result = service.applyFrozenPr("p1", "author", KEY, List.of("c1", "c2", "undone"), List.of("cs1"), Map.of());

        assertTrue(result.isSuccess());
        assertEquals(3, result.getAppliedCount());
        verify(datasetService).moveDraftToMain("p1", KEY);
    }

    @Test
    void whenPublicMovedTheFrozenCopyIsMergedWithTheChosenResolutions() throws Exception {
        DraftPublishDiff prepared = diff(true, true, Set.of());
        when(draftPublishMergeService.comparePublish("p1", KEY)).thenReturn(prepared);

        var result = service.applyFrozenPr("p1", "author", KEY, List.of("c1"), List.of("cs1"), Map.of());

        assertTrue(result.isSuccess());
        verify(draftPublishMergeService).publishReviewed("p1", KEY, Map.of());
        verify(datasetService, never()).moveDraftToMain(any(), any());
        verify(draftChangeHistory).markChangeSetsPublished("p1", List.of("cs1"));
    }

    @Test
    void itemsChangedOnBothSidesNeedAChoiceAndNothingIsPublished() throws Exception {
        DraftPublishDiff prepared = diff(true, true, Set.of("urn:x#A"));
        when(draftPublishMergeService.comparePublish("p1", KEY)).thenReturn(prepared);

        var result = service.applyFrozenPr("p1", "author", KEY, List.of("c1"), List.of("cs1"), Map.of());

        assertFalse(result.isSuccess());
        assertTrue(result.isConflictBlocked());
        verify(draftPublishMergeService, never()).publishReviewed(any(), any(), anyMap());
        verify(draftChangeHistory, never()).markChangeSetsPublished(any(), anyList());
    }

    @Test
    void aFrozenCopyThatIsNotReadyIsRefused() {
        when(draftCopyService.isReady("p1", KEY)).thenReturn(false);

        var result = service.applyFrozenPr("p1", "author", KEY, List.of("c1"), List.of("cs1"), Map.of());

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("not ready"));
        verify(datasetService, never()).moveDraftToMain(any(), any());
    }

    @Test
    void ifPublicMovedAndTheComparisonIsUnavailableThePublishIsRefusedInsteadOfOverwritingPublic() throws Exception {
        DraftPublishDiff prepared = diff(false, false, Set.of());
        when(draftPublishMergeService.comparePublish("p1", KEY)).thenReturn(prepared);
        when(mainGraphRevisionService.getRevision("p1")).thenReturn(8L);

        var result = service.applyFrozenPr("p1", "author", KEY, List.of("c1"), List.of("cs1"), Map.of());

        assertFalse(result.isSuccess());
        assertTrue(result.isConflictBlocked());
        verify(datasetService, never()).moveDraftToMain(any(), any());
    }

    @Test
    void aMoveThatFailsLeavesTheHistoryAndTheDraftRowsAlone() throws Exception {
        DraftPublishDiff prepared = diff(true, false, Set.of());
        when(draftPublishMergeService.comparePublish("p1", KEY)).thenReturn(prepared);
        doThrow(new RuntimeException("fuseki down")).when(datasetService).moveDraftToMain("p1", KEY);

        var result = service.applyFrozenPr("p1", "author", KEY, List.of("c1"), List.of("cs1"), Map.of());

        assertFalse(result.isSuccess());
        verify(draftChangeHistory, never()).markChangeSetsPublished(any(), anyList());
        verify(draftRepository, never()).saveAll(anyList());
    }
}
