package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.DraftCopyStatus;
import self.research.ontology.owlEditor.model.DraftSession;
import self.research.ontology.owlEditor.repository.DraftSessionRepository;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrSnapshotServiceTest {

    private static final String P = "proj";

    private final DraftSessionRepository sessions = mock(DraftSessionRepository.class);
    private final SparqlDatasetService dataset = mock(SparqlDatasetService.class);
    private final DraftPublishMergeService merge = mock(DraftPublishMergeService.class);
    private final DraftPublishService publish = mock(DraftPublishService.class);
    private final DraftTrackingService tracking = mock(DraftTrackingService.class);
    private final self.research.ontology.owlEditor.repository.DraftPullRequestRepository pullRequests =
            mock(self.research.ontology.owlEditor.repository.DraftPullRequestRepository.class);
    private PrSnapshotService service;

    @BeforeEach
    void setUp() throws IOException {
        service = new PrSnapshotService(sessions, dataset, merge, publish, new ProjectWriteLockRegistry(), tracking, pullRequests);
        DraftChange first = new DraftChange();
        first.setId("c1");
        DraftChange second = new DraftChange();
        second.setId("c2");
        when(tracking.getUnappliedDraftsForUser(P, "author")).thenReturn(java.util.List.of(first, second));
        when(tracking.draftChangeSetIds(P, "author")).thenReturn(java.util.List.of("cs1"));
        DraftSession author = new DraftSession(P, "author", 7, 120);
        author.setBaselineAt(LocalDateTime.of(2026, 10, 24, 1, 0));
        author.setPrefixes(Map.of("ex", "http://example.org/"));
        author.setCopyStatus(DraftCopyStatus.READY);
        when(sessions.findByProjectIdAndUserId(P, "author")).thenReturn(Optional.of(author));
        when(merge.baselineOf(P, "author")).thenReturn(Optional.of("<rdf/>"));
        when(merge.writeBaselineFor(P, "pr-snapshot-pr1", "<rdf/>")).thenReturn("baselines/pr-snapshot-pr1.owl");
    }

    @Test
    void anUpdateBuildsTheNewCopyUnderTheOtherKeyAndLeavesTheOldOneAlone() throws IOException {
        when(merge.writeBaselineFor(P, "pr-snapshot-pr1-b", "<rdf/>")).thenReturn("baselines/b.owl");

        PrSnapshotService.Frozen frozen = service.freeze(P, "author", "pr1", "pr-snapshot-pr1");

        assertEquals("pr-snapshot-pr1-b", frozen.key());
        verify(dataset).copyDraftGraph(P, "author", "pr-snapshot-pr1-b");
        verify(dataset, never()).clearDraftGraph(P, "pr-snapshot-pr1");
        verify(publish, never()).clearBaseline(P, "pr-snapshot-pr1");
    }

    @Test
    void theChangeSetIdsComeFromThePrsOwnRecordsAsWellAsTheHistory() {
        DraftChange withSet = new DraftChange();
        withSet.setId("c9");
        withSet.setOperationData(new java.util.HashMap<>(Map.of("changeSetIds", java.util.List.of("cs7"))));
        when(tracking.getUnappliedDraftsForUser(P, "author")).thenReturn(java.util.List.of(withSet));

        PrSnapshotService.Frozen frozen = service.freeze(P, "author", "pr1");

        assertEquals(java.util.List.of("cs7", "cs1"), frozen.changeSetIds());
    }

    @Test
    void leftoverCopiesOfPrsThatAreNotOpenAnymoreAreRemovedAtStartup() {
        DraftSession live = new DraftSession(P, "pr-snapshot-pr1", 1, 1);
        DraftSession done = new DraftSession(P, "pr-snapshot-pr2", 1, 1);
        DraftSession stale = new DraftSession(P, "pr-snapshot-pr3-b", 1, 1);
        when(sessions.findByUserIdStartingWith("pr-snapshot-")).thenReturn(java.util.List.of(live, done, stale));
        self.research.ontology.owlEditor.model.DraftPullRequest open = new self.research.ontology.owlEditor.model.DraftPullRequest();
        open.setStatus(self.research.ontology.owlEditor.model.DraftPullRequest.Status.OPEN);
        open.setSnapshotKey("pr-snapshot-pr1");
        self.research.ontology.owlEditor.model.DraftPullRequest merged = new self.research.ontology.owlEditor.model.DraftPullRequest();
        merged.setStatus(self.research.ontology.owlEditor.model.DraftPullRequest.Status.APPROVED);
        merged.setSnapshotKey("pr-snapshot-pr2");
        self.research.ontology.owlEditor.model.DraftPullRequest movedOn = new self.research.ontology.owlEditor.model.DraftPullRequest();
        movedOn.setStatus(self.research.ontology.owlEditor.model.DraftPullRequest.Status.OPEN);
        movedOn.setSnapshotKey("pr-snapshot-pr3");
        when(pullRequests.findById("pr1")).thenReturn(Optional.of(open));
        when(pullRequests.findById("pr2")).thenReturn(Optional.of(merged));
        when(pullRequests.findById("pr3")).thenReturn(Optional.of(movedOn));

        assertEquals(2, service.sweepOrphans());

        verify(dataset, never()).clearDraftGraph(P, "pr-snapshot-pr1");
        verify(dataset).clearDraftGraph(P, "pr-snapshot-pr2");
        verify(dataset).clearDraftGraph(P, "pr-snapshot-pr3-b");
    }

    @Test
    void theNextUpdateGoesBackToTheFirstKey() {
        assertEquals("pr-snapshot-pr1", PrSnapshotService.alternateKey("pr1", "pr-snapshot-pr1-b"));
        assertEquals("pr-snapshot-pr1-b", PrSnapshotService.alternateKey("pr1", "pr-snapshot-pr1"));
        assertEquals("pr-snapshot-pr1", PrSnapshotService.alternateKey("pr1", null));
        assertEquals("pr1", PrSnapshotService.prIdOfKey("pr-snapshot-pr1-b"));
        assertEquals("pr1", PrSnapshotService.prIdOfKey("pr-snapshot-pr1"));
    }

    @Test
    void aFailedUpdateDropsOnlyTheNewCopy() throws IOException {
        doThrow(new IOException("disk full")).when(merge).writeBaselineFor(P, "pr-snapshot-pr1-b", "<rdf/>");

        assertThrows(IllegalStateException.class, () -> service.freeze(P, "author", "pr1", "pr-snapshot-pr1"));

        verify(dataset, org.mockito.Mockito.atLeastOnce()).clearDraftGraph(P, "pr-snapshot-pr1-b");
        verify(dataset, never()).clearDraftGraph(P, "pr-snapshot-pr1");
    }

    @Test
    void freezingCopiesTheDraftGraphAndCreatesAReadySessionForTheSnapshot() {
        PrSnapshotService.Frozen frozen = service.freeze(P, "author", "pr1");

        assertEquals("pr-snapshot-pr1", frozen.key());
        assertEquals(java.util.List.of("c1", "c2"), frozen.changeIds());
        assertEquals(java.util.List.of("cs1"), frozen.changeSetIds());
        verify(dataset).copyDraftGraph(P, "author", "pr-snapshot-pr1");
        ArgumentCaptor<DraftSession> saved = ArgumentCaptor.forClass(DraftSession.class);
        verify(sessions).save(saved.capture());
        DraftSession snapshot = saved.getValue();
        assertEquals("pr-snapshot-pr1", snapshot.getUserId());
        assertEquals(7, snapshot.getBaselineMainRevision());
        assertEquals(120, snapshot.getBaselineMainTripleCount());
        assertEquals(DraftCopyStatus.READY, snapshot.getCopyStatus());
        assertEquals("baselines/pr-snapshot-pr1.owl", snapshot.getBaselineSnapshotPath());
        assertEquals(Map.of("ex", "http://example.org/"), snapshot.getPrefixes());
    }

    @Test
    void freezingIsRefusedWithAClearMessageWhenThereIsNoDraft() {
        when(sessions.findByProjectIdAndUserId(P, "author")).thenReturn(Optional.empty());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.freeze(P, "author", "pr1"));

        assertTrue(error.getMessage().contains("draft is not ready"));
        verify(dataset, never()).copyDraftGraph(anyString(), anyString(), anyString());
    }

    @Test
    void freezingIsRefusedWhenTheDraftHasNoStartingVersion() {
        when(merge.baselineOf(P, "author")).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.freeze(P, "author", "pr1"));
        verify(dataset, never()).copyDraftGraph(anyString(), anyString(), anyString());
    }

    @Test
    void ifTheCopyFailsNothingIsLeftBehind() {
        doThrow(new RuntimeException("fuseki down")).when(dataset).copyDraftGraph(P, "author", "pr-snapshot-pr1");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.freeze(P, "author", "pr1"));

        assertTrue(error.getMessage().contains("fuseki down"));
        verify(publish, org.mockito.Mockito.atLeastOnce()).clearBaseline(P, "pr-snapshot-pr1");
        verify(dataset, org.mockito.Mockito.atLeastOnce()).clearDraftGraph(P, "pr-snapshot-pr1");
        verify(sessions, never()).save(org.mockito.ArgumentMatchers.any(DraftSession.class));
    }

    @Test
    void droppingClearsTheGraphTheSessionAndTheSavedStartingVersion() {
        service.drop(P, "pr1");

        verify(publish).clearBaseline(P, "pr-snapshot-pr1");
        verify(dataset).clearDraftGraph(P, "pr-snapshot-pr1");
    }

    @Test
    void droppingKeepsGoingWhenOneStepFails() {
        doThrow(new RuntimeException("gridfs down")).when(publish).clearBaseline(P, "pr-snapshot-pr1");

        service.drop(P, "pr1");

        verify(dataset).clearDraftGraph(P, "pr-snapshot-pr1");
    }

    @Test
    void droppingEverythingForAProjectDropsOnlyThePrsThatHaveASnapshot() {
        self.research.ontology.owlEditor.model.DraftPullRequest frozen = new self.research.ontology.owlEditor.model.DraftPullRequest();
        frozen.setId("pr1");
        frozen.setSnapshotKey("pr-snapshot-pr1");
        self.research.ontology.owlEditor.model.DraftPullRequest legacy = new self.research.ontology.owlEditor.model.DraftPullRequest();
        legacy.setId("pr2");
        when(pullRequests.findByProjectIdOrderByCreatedAtDesc(P)).thenReturn(java.util.List.of(frozen, legacy));

        service.dropAllForProject(P);

        verify(dataset).clearDraftGraph(P, "pr-snapshot-pr1");
        verify(dataset, never()).clearDraftGraph(P, "pr-snapshot-pr2");
    }
}
