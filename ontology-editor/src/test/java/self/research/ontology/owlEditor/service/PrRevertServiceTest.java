package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import self.research.ontology.owlEditor.model.DraftPullRequest;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.repository.DraftPullRequestRepository;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Actor;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Item;
import self.research.ontology.owlEditor.service.ChangeRollbackService.Result;
import self.research.ontology.owlEditor.service.delta.ExactDelta;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrRevertServiceTest {

    private static final String P = "proj";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 22, 1, 0);

    private final DraftPullRequestRepository prs = mock(DraftPullRequestRepository.class);
    private final HistorySyncService historySync = mock(HistorySyncService.class);
    private final ChangeRollbackService rollback = mock(ChangeRollbackService.class);
    private final DraftTrackingService drafts = mock(DraftTrackingService.class);
    private final DraftMergeApplier merges = mock(DraftMergeApplier.class);
    private final WorkspaceOwnershipService ownership = mock(WorkspaceOwnershipService.class);
    private final PrRevertService service = new PrRevertService(prs, historySync, rollback, drafts, merges, ownership);

    private DraftPullRequest pr;

    @BeforeEach
    void setUp() {
        pr = new DraftPullRequest();
        pr.setId("pr1");
        pr.setProjectId(P);
        pr.setAuthorId("author");
        pr.setTitle("Add toppings");
        pr.setStatus(DraftPullRequest.Status.APPROVED);
        pr.setPublishedChangeSetIds(List.of("cs-old", "cs-new"));
        when(prs.findById("pr1")).thenReturn(Optional.of(pr));
        when(ownership.canPublishToProject("owner", P)).thenReturn(true);
        when(ownership.canPublishToProject("admin", P)).thenReturn(true);
        when(ownership.canPublishToProject("editor", P)).thenReturn(true);
        when(historySync.getChangeSet(P, "cs-old")).thenReturn(List.of(row("a", "cs-old", T0, null, "<urn:a> <urn:p> <urn:b> .\n")));
        when(historySync.getChangeSet(P, "cs-new")).thenReturn(List.of(row("b", "cs-new", T0.plusMinutes(5), "<urn:a> <urn:p> <urn:b> .\n", "<urn:a> <urn:p> <urn:c> .\n")));
    }

    private static HistoryChange row(String id, String changeSet, LocalDateTime at, String deleted, String inserted) {
        HistoryChange change = new HistoryChange();
        change.setId(id);
        change.setChangeSetId(changeSet);
        change.setTimestamp(at);
        change.setOperationType("ontologyMerge");
        change.setExactDeleted(deleted);
        change.setExactInserted(inserted);
        return change;
    }

    private static Result ok(boolean dryRun, int items) {
        List<Item> applied = new java.util.ArrayList<>();
        for (int i = 0; i < items; i++) {
            applied.add(new Item("c" + i, null, null, null, null, null));
        }
        return new Result(200, true, dryRun, "UNDO", "cs", applied, List.of(), null, null);
    }

    private static Result blocked(String reason) {
        return new Result(200, false, true, "UNDO", "cs", List.of(), List.of(new Item("c", null, null, null, null, reason)), null, null);
    }

    private static Result alreadyUndone() {
        return new Result(200, false, true, "UNDO", "cs", List.of(), List.of(new Item("c", null, null, null, null, "Already undone")), null, null);
    }

    @Test
    void aViewerOrDraftEditorCannotRevert() {
        when(ownership.canPublishToProject("viewer", P)).thenReturn(false);
        assertEquals(403, service.revert(P, "pr1", "viewer", "viewer", "why", true).status());
    }

    @Test
    void anOwnerAnAdminAndAnEditorCanRevert() {
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), eq(true))).thenReturn(ok(true, 1));
        assertTrue(service.revert(P, "pr1", "owner", "Owner", null, true).ok());
        assertTrue(service.revert(P, "pr1", "admin", "Admin", null, true).ok());
        assertTrue(service.revert(P, "pr1", "editor", "Editor", null, true).ok());
    }

    @Test
    void aDraftEditorOrViewerCannotRevert() {
        when(ownership.canPublishToProject("draft-editor", P)).thenReturn(false);
        PrRevertService.Outcome outcome = service.revert(P, "pr1", "draft-editor", "Draft editor", "why", true);
        assertEquals(403, outcome.status());
        assertEquals("Only project owners, admins, or editors can revert pull requests", outcome.message());
    }

    @Test
    void applyingNeedsAReasonButThePreviewDoesNot() {
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), anyBoolean())).thenReturn(ok(true, 1));
        assertTrue(service.revert(P, "pr1", "owner", "Owner", "  ", true).ok());
        PrRevertService.Outcome outcome = service.revert(P, "pr1", "owner", "Owner", "  ", false);
        assertEquals(400, outcome.status());
        assertEquals("Please give a reason for reverting this pull request", outcome.message());
        verify(rollback, never()).undoChangeSet(anyString(), anyString(), any(Actor.class), eq(false));
    }

    @Test
    void theRevertIsPreviewedNewestChangeSetFirstAndChangesNothing() {
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), eq(true))).thenReturn(ok(true, 2));

        PrRevertService.Outcome outcome = service.revert(P, "pr1", "owner", "Owner", null, true);

        assertTrue(outcome.ok());
        assertEquals("cs-new", outcome.changeSets().get(0).get("changeSetId"));
        assertEquals("cs-old", outcome.changeSets().get(1).get("changeSetId"));
        verify(rollback, never()).undoChangeSet(anyString(), anyString(), any(Actor.class), eq(false));
        assertEquals(DraftPullRequest.Status.APPROVED, pr.getStatus());
    }

    @Test
    void oneBlockedChangeSetBlocksTheWholeRevertAndNothingIsApplied() {
        when(rollback.undoChangeSet(eq(P), eq("cs-new"), any(Actor.class), anyBoolean())).thenReturn(ok(true, 1));
        when(rollback.undoChangeSet(eq(P), eq("cs-old"), any(Actor.class), anyBoolean())).thenReturn(blocked("Others have added items that use the new name of X"));

        PrRevertService.Outcome outcome = service.revert(P, "pr1", "owner", "Owner", "Wrong merge order", false);

        assertEquals(409, outcome.status());
        assertFalse(outcome.ok());
        assertEquals(List.of("Others have added items that use the new name of X"), outcome.changeSets().get(1).get("reasons"));
        verify(rollback, never()).undoChangeSet(anyString(), anyString(), any(Actor.class), eq(false));
        assertEquals(DraftPullRequest.Status.APPROVED, pr.getStatus());
    }

    @Test
    void aCleanRevertUndoesEveryChangeSetThenMarksThePrRevertedAndRecordsIt() {
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), anyBoolean())).thenReturn(ok(false, 1));

        PrRevertService.Outcome outcome = service.revert(P, "pr1", "owner", "Owner", "Wrong merge order", false);

        assertTrue(outcome.ok());
        assertEquals(DraftPullRequest.Status.REVERTED, pr.getStatus());
        assertEquals("owner", pr.getRevertedBy());
        ArgumentCaptor<Actor> actors = ArgumentCaptor.forClass(Actor.class);
        verify(rollback).undoChangeSet(eq(P), eq("cs-new"), actors.capture(), eq(false));
        assertEquals("pr1:" + pr.getRevertStartedAt().toEpochMilli() + ":cs-new", actors.getValue().opId());
        verify(drafts).recordPrReversal(P, "pr1", "Add toppings", "Owner", 2, "Wrong merge order");
        verify(historySync).markPrPublicationReverted(P, "pr1");
        assertEquals("Wrong merge order", pr.getRevertReason());
    }

    @Test
    void aChangeSetThatIsAlreadyUndoneIsSkippedAndTheRevertStillCompletes() {
        when(rollback.undoChangeSet(eq(P), eq("cs-new"), any(Actor.class), anyBoolean())).thenReturn(alreadyUndone());
        when(rollback.undoChangeSet(eq(P), eq("cs-old"), any(Actor.class), anyBoolean())).thenReturn(ok(false, 1));

        assertTrue(service.revert(P, "pr1", "owner", "Owner", "Wrong merge order", false).ok());
        verify(rollback).undoChangeSet(eq(P), eq("cs-new"), any(Actor.class), eq(false));
        assertEquals(DraftPullRequest.Status.REVERTED, pr.getStatus());
    }

    @Test
    void aFailureWhileApplyingStopsAndLeavesThePrMerged() {
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), eq(true))).thenReturn(ok(true, 1));
        when(rollback.undoChangeSet(eq(P), eq("cs-new"), any(Actor.class), eq(false))).thenReturn(ok(false, 1));
        when(rollback.undoChangeSet(eq(P), eq("cs-old"), any(Actor.class), eq(false))).thenReturn(blocked("changed meanwhile"));

        PrRevertService.Outcome outcome = service.revert(P, "pr1", "owner", "Owner", "Wrong merge order", false);

        assertEquals(409, outcome.status());
        assertTrue(outcome.message().startsWith("Reverted 1 of 2 change sets, then stopped"));
        assertEquals(DraftPullRequest.Status.APPROVED, pr.getStatus());
        verify(drafts, never()).recordPrReversal(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    private static Result nothingLeft(boolean dryRun) {
        return new Result(200, !dryRun ? false : true, dryRun, "UNDO", "cs", List.of(), List.of(), null, "Nothing to undo");
    }

    private static Result partlySkipped(String reason) {
        return new Result(200, true, true, "UNDO", "cs", List.of(new Item("c", null, "urn:x", "Pizzanew", null, null)),
                List.of(new Item("d", null, "urn:y", "Other", null, reason)), null, null);
    }

    private static Result notUndone() {
        return new Result(200, true, false, "REDO", "cs", List.of(),
                List.of(new Item("c", null, "urn:x", "X", null, "It hasn't been undone")), null, null);
    }

    @Test
    void applyingAgainRedoesTheChangeSetsOldestFirstAndMakesThePrMergedAgain() {
        pr.setStatus(DraftPullRequest.Status.REVERTED);
        pr.setRevertStartedAt(java.time.Instant.now());
        when(rollback.redoChangeSetForPr(eq(P), anyString(), any(Actor.class), anyBoolean())).thenReturn(ok(false, 1));

        PrRevertService.Outcome outcome = service.reapply(P, "pr1", "owner", "Owner", false);

        assertTrue(outcome.ok());
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(rollback, historySync);
        order.verify(rollback).redoChangeSetForPr(eq(P), eq("cs-old"), any(Actor.class), eq(false));
        order.verify(rollback).redoChangeSetForPr(eq(P), eq("cs-new"), any(Actor.class), eq(false));
        order.verify(historySync).markPrPublicationRestored(P, "pr1");
        verify(historySync).markPrReversalUndone(P, "pr1");
        verify(historySync).markPrRowsRestored(P, "pr1");
        assertEquals(DraftPullRequest.Status.APPROVED, pr.getStatus());
        assertEquals(null, pr.getRevertStartedAt());
        assertEquals(null, pr.getRevertReason());
    }

    @Test
    void onlyAReversedPrCanBeAppliedAgainAndOnlyByAnOwnerAdminOrEditor() {
        assertEquals(409, service.reapply(P, "pr1", "owner", "Owner", false).status());
        pr.setStatus(DraftPullRequest.Status.REVERTED);
        assertEquals(403, service.reapply(P, "pr1", "viewer", "Viewer", false).status());
    }

    @Test
    void thePreviewForApplyingAgainDoesNotBlockOnAnEntityAnEarlierSetOfThePrWillBringBack() {
        pr.setStatus(DraftPullRequest.Status.REVERTED);
        when(rollback.redoChangeSetForPr(eq(P), anyString(), any(Actor.class), eq(true))).thenReturn(ok(true, 1));
        when(rollback.redoChangeSetForPr(eq(P), eq("cs-new"), any(Actor.class), eq(true)))
                .thenReturn(blocked("It no longer exists"));

        PrRevertService.Outcome preview = service.reapply(P, "pr1", "owner", "Owner", true);

        assertTrue(preview.ok());
    }

    @Test
    void applyingAgainStopsForRealWhenTheEntityIsStillMissingAtItsTurn() {
        pr.setStatus(DraftPullRequest.Status.REVERTED);
        when(rollback.redoChangeSetForPr(eq(P), anyString(), any(Actor.class), eq(true))).thenReturn(ok(true, 1));
        when(rollback.redoChangeSetForPr(eq(P), anyString(), any(Actor.class), eq(false))).thenReturn(ok(false, 1));
        when(rollback.redoChangeSetForPr(eq(P), eq("cs-new"), any(Actor.class), eq(false)))
                .thenReturn(blocked("It no longer exists"));

        PrRevertService.Outcome outcome = service.reapply(P, "pr1", "owner", "Owner", false);

        assertEquals(409, outcome.status());
        assertEquals(DraftPullRequest.Status.REVERTED, pr.getStatus());
        verify(rollback).redoChangeSetForPr(eq(P), eq("cs-old"), any(Actor.class), eq(false));
    }

    @Test
    void aSecondRevertAfterApplyingAgainDoesNotReuseTheFirstRevertsOperationKeys() {
        pr.setRevertStartedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), anyBoolean())).thenReturn(ok(false, 1));
        when(rollback.redoChangeSetForPr(eq(P), anyString(), any(Actor.class), anyBoolean())).thenReturn(ok(false, 1));
        org.mockito.ArgumentCaptor<Actor> undoKeys = org.mockito.ArgumentCaptor.forClass(Actor.class);
        org.mockito.ArgumentCaptor<Actor> redoKeys = org.mockito.ArgumentCaptor.forClass(Actor.class);

        assertTrue(service.revert(P, "pr1", "owner", "Owner", "First", false).ok());
        pr.setRevertedAt(java.time.Instant.parse("2026-01-01T01:00:00Z"));
        assertTrue(service.reapply(P, "pr1", "owner", "Owner", false).ok());
        pr.setRevertedAt(java.time.Instant.parse("2026-01-01T02:00:00Z"));
        pr.setStatus(DraftPullRequest.Status.APPROVED);
        assertTrue(service.revert(P, "pr1", "owner", "Owner", "Second", false).ok());
        pr.setRevertedAt(java.time.Instant.parse("2026-01-01T03:00:00Z"));
        assertTrue(service.reapply(P, "pr1", "owner", "Owner", false).ok());

        verify(rollback, org.mockito.Mockito.atLeastOnce()).undoChangeSet(eq(P), anyString(), undoKeys.capture(), eq(false));
        verify(rollback, org.mockito.Mockito.atLeastOnce()).redoChangeSetForPr(eq(P), anyString(), redoKeys.capture(), eq(false));
        java.util.List<String> undo = undoKeys.getAllValues().stream().map(Actor::opId).toList();
        java.util.List<String> redo = redoKeys.getAllValues().stream().map(Actor::opId).toList();
        assertEquals(undo.size(), new java.util.HashSet<>(undo).size());
        assertEquals(redo.size(), new java.util.HashSet<>(redo).size());
    }

    @Test
    void applyingAgainLeavesAloneASetThatTheRevertNeverUndid() {
        pr.setStatus(DraftPullRequest.Status.REVERTED);
        when(historySync.setsUndoneForPr(P, "pr1")).thenReturn(java.util.Set.of("cs-new"));
        when(rollback.redoChangeSetForPr(eq(P), anyString(), any(Actor.class), anyBoolean())).thenReturn(ok(false, 1));

        PrRevertService.Outcome outcome = service.reapply(P, "pr1", "owner", "Owner", false);

        assertTrue(outcome.ok());
        verify(rollback, never()).redoChangeSetForPr(eq(P), eq("cs-old"), any(Actor.class), anyBoolean());
        verify(rollback).redoChangeSetForPr(eq(P), eq("cs-new"), any(Actor.class), eq(false));
    }

    @Test
    void confirmingApplyAgainRunsTheSetsEvenWhenThePreviewSawAnEntityThatAnEarlierSetBringsBack() {
        pr.setStatus(DraftPullRequest.Status.REVERTED);
        when(rollback.redoChangeSetForPr(eq(P), anyString(), any(Actor.class), eq(true))).thenReturn(ok(true, 1));
        when(rollback.redoChangeSetForPr(eq(P), eq("cs-new"), any(Actor.class), eq(true)))
                .thenReturn(blocked("It no longer exists"));
        when(rollback.redoChangeSetForPr(eq(P), anyString(), any(Actor.class), eq(false))).thenReturn(ok(false, 1));

        PrRevertService.Outcome outcome = service.reapply(P, "pr1", "owner", "Owner", false);

        assertTrue(outcome.ok());
        assertEquals(DraftPullRequest.Status.APPROVED, pr.getStatus());
    }

    @Test
    void applyingAgainIsRefusedWhenAChangeSetIsBlockedAndNothingIsApplied() {
        pr.setStatus(DraftPullRequest.Status.REVERTED);
        when(rollback.redoChangeSetForPr(eq(P), anyString(), any(Actor.class), eq(true))).thenReturn(ok(true, 1));
        when(rollback.redoChangeSetForPr(eq(P), eq("cs-new"), any(Actor.class), eq(true)))
                .thenReturn(blocked("It already exists again"));

        PrRevertService.Outcome outcome = service.reapply(P, "pr1", "owner", "Owner", true);

        assertEquals(409, outcome.status());
        verify(rollback, never()).redoChangeSetForPr(eq(P), anyString(), any(Actor.class), eq(false));
        assertEquals(DraftPullRequest.Status.REVERTED, pr.getStatus());
    }

    @Test
    void aChangeSetThatWasNeverUndoneIsFineWhenApplyingAgain() {
        pr.setStatus(DraftPullRequest.Status.REVERTED);
        when(rollback.redoChangeSetForPr(eq(P), eq("cs-old"), any(Actor.class), anyBoolean())).thenReturn(notUndone());
        when(rollback.redoChangeSetForPr(eq(P), eq("cs-new"), any(Actor.class), anyBoolean())).thenReturn(ok(false, 1));

        assertTrue(service.reapply(P, "pr1", "owner", "Owner", false).ok());
        verify(rollback).redoChangeSetForPr(eq(P), eq("cs-old"), any(Actor.class), eq(false));
        assertEquals(DraftPullRequest.Status.APPROVED, pr.getStatus());
    }

    @Test
    void aRevertTagsItsOwnUndoRowsToThePrSoTheyAreNotListedOneByOne() {
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), anyBoolean())).thenReturn(ok(false, 1));

        service.revert(P, "pr1", "owner", "Owner", "Wrong merge order", false);

        verify(historySync, org.mockito.Mockito.atLeastOnce()).tagRollbackRowsOfPr(eq(P), any(), eq("pr1"));
    }

    @Test
    void everySetIsJudgedWhenItsTurnComesNotByTheEarlierPreview() {
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), eq(true))).thenReturn(alreadyUndone());
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), eq(false))).thenReturn(ok(false, 1));

        assertTrue(service.revert(P, "pr1", "owner", "Owner", "Wrong merge order", false).ok());

        verify(rollback).undoChangeSet(eq(P), eq("cs-new"), any(Actor.class), eq(false));
        verify(rollback).undoChangeSet(eq(P), eq("cs-old"), any(Actor.class), eq(false));
    }

    @Test
    void aLaterChangeOfTheSamePrDoesNotBlockThePreviewButStillBlocksTheRealRun() {
        Result laterFirst = new Result(200, true, true, "UNDO", "cs", List.of(),
                List.of(new Item("c", null, "urn:x", "X", null, "Later changes come first. Undo these before this one: Y")), null, null);
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), eq(true))).thenReturn(ok(true, 1));
        when(rollback.undoChangeSet(eq(P), eq("cs-old"), any(Actor.class), eq(true))).thenReturn(laterFirst);

        assertEquals(200, service.revert(P, "pr1", "owner", "Owner", null, true).status());

        when(rollback.undoChangeSet(eq(P), eq("cs-new"), any(Actor.class), eq(false))).thenReturn(ok(false, 1));
        when(rollback.undoChangeSet(eq(P), eq("cs-old"), any(Actor.class), eq(false))).thenReturn(
                new Result(200, true, false, "UNDO", "cs", List.of(),
                        List.of(new Item("c", null, "urn:x", "X", null, "Later changes come first. Undo these before this one: Z")), null, null));
        PrRevertService.Outcome outcome = service.revert(P, "pr1", "owner", "Owner", "Wrong merge order", false);

        assertEquals(409, outcome.status());
        assertEquals(DraftPullRequest.Status.APPROVED, pr.getStatus());
    }

    @Test
    void theUndoAndRedoRowsOfEachStepAreTaggedAsTheyAreWrittenSoTheyNeverShowOneByOne() {
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), anyBoolean())).thenReturn(ok(false, 1));
        service.revert(P, "pr1", "owner", "Owner", "Wrong merge order", false);
        verify(historySync, org.mockito.Mockito.atLeast(2)).tagRollbackRowsOfPr(eq(P), any(), eq("pr1"));

        pr.setStatus(DraftPullRequest.Status.REVERTED);
        org.mockito.Mockito.clearInvocations(historySync);
        when(rollback.redoChangeSetForPr(eq(P), anyString(), any(Actor.class), anyBoolean())).thenReturn(ok(false, 1));
        service.reapply(P, "pr1", "owner", "Owner", false);
        verify(historySync, org.mockito.Mockito.atLeast(2)).tagRollbackRowsOfPr(eq(P), any(), eq("pr1"));
    }

    @Test
    void aChangeSetWithNothingLeftToRemoveDoesNotStopTheRevert() {
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), eq(true))).thenReturn(ok(true, 1));
        when(rollback.undoChangeSet(eq(P), eq("cs-new"), any(Actor.class), eq(false))).thenReturn(nothingLeft(false));
        when(rollback.undoChangeSet(eq(P), eq("cs-old"), any(Actor.class), eq(false))).thenReturn(ok(false, 1));

        PrRevertService.Outcome outcome = service.revert(P, "pr1", "owner", "Owner", "Wrong merge order", false);

        assertTrue(outcome.ok());
        assertEquals(DraftPullRequest.Status.REVERTED, pr.getStatus());
    }

    @Test
    void aPreviewThatReportsSkippedItemsCountsAsBlockedEvenWhenTheCallItselfSucceeded() {
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), eq(true))).thenReturn(ok(true, 1));
        when(rollback.undoChangeSet(eq(P), eq("cs-old"), any(Actor.class), eq(true)))
                .thenReturn(partlySkipped("It was changed since this edit"));

        PrRevertService.Outcome outcome = service.revert(P, "pr1", "owner", "Owner", null, true);

        assertEquals(409, outcome.status());
        verify(rollback, never()).undoChangeSet(eq(P), anyString(), any(Actor.class), eq(false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePreviewListsTheChangesThatWillBeRemoved() {
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), eq(true))).thenReturn(new Result(200, true, true, "UNDO", "cs",
                List.of(new Item("c", null, "urn:x#Pizzanew", "Pizzanew", "http://x#subClassOf", null)), List.of(), null, null));

        PrRevertService.Outcome outcome = service.revert(P, "pr1", "owner", "Owner", null, true);

        java.util.Map<String, Object> first = (java.util.Map<String, Object>) outcome.changeSets().get(0);
        java.util.List<java.util.Map<String, Object>> items = (java.util.List<java.util.Map<String, Object>>) first.get("items");
        assertEquals(historySync.getChangeSet(P, "cs-new").size(), items.size());
        assertEquals(PrRevertService.describe(historySync.getChangeSet(P, "cs-new").get(0)), items.get(0).get("label"));
    }

    @Test
    void anEntityThatIsAlreadyGoneIsNothingToRemoveAndDoesNotBlockTheRevert() {
        Result gone = new Result(200, false, true, "UNDO", "cs", List.of(),
                List.of(new Item("c", null, "urn:x", "PizzaThing", null, "It no longer exists")), null, null);
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), eq(true))).thenReturn(ok(true, 1));
        when(rollback.undoChangeSet(eq(P), eq("cs-old"), any(Actor.class), eq(true))).thenReturn(gone);
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), eq(false))).thenReturn(ok(false, 1));
        when(rollback.undoChangeSet(eq(P), eq("cs-old"), any(Actor.class), eq(false))).thenReturn(gone);

        PrRevertService.Outcome preview = service.revert(P, "pr1", "owner", "Owner", null, true);
        assertEquals(200, preview.status());

        assertTrue(service.revert(P, "pr1", "owner", "Owner", "Wrong merge order", false).ok());
        assertEquals(DraftPullRequest.Status.REVERTED, pr.getStatus());
    }

    @Test
    void theDescriptionReadsLikeTheCreateClassWordsOfTheUndoMenu() {
        HistoryChange created = new HistoryChange();
        created.setOperationType("createClass");
        created.setEntityLabel("PizzaThing");
        HistoryChange merge = new HistoryChange();
        merge.setOperationType("ontologyMerge");
        merge.setEntityLabel("Ontology merge");

        assertEquals("Create class PizzaThing", PrRevertService.describe(created));
        assertEquals("Ontology merge", PrRevertService.describe(merge));

        created.setEntityLabel("Create class PizzaThing");
        assertEquals("Create class PizzaThing", PrRevertService.describe(created));
    }

    @Test
    void whenTheRevertStopsTheMessageSaysWhy() {
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), eq(true))).thenReturn(ok(true, 1));
        when(rollback.undoChangeSet(eq(P), eq("cs-new"), any(Actor.class), eq(false))).thenReturn(ok(false, 1));
        when(rollback.undoChangeSet(eq(P), eq("cs-old"), any(Actor.class), eq(false))).thenReturn(blocked("changed meanwhile"));

        PrRevertService.Outcome outcome = service.revert(P, "pr1", "owner", "Owner", "Wrong merge order", false);

        assertTrue(outcome.message().contains("changed meanwhile"));
        assertTrue(outcome.message().contains("Confirm again to continue"));
    }

    @Test
    void theMergeRecordIsMarkedRevertedBeforeThePrItselfIsSaved() {
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), anyBoolean())).thenReturn(ok(false, 1));

        service.revert(P, "pr1", "owner", "Owner", "Wrong merge order", false);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(historySync, prs);
        order.verify(historySync).markPrPublicationReverted(P, "pr1");
        order.verify(historySync).markPrRowsReverted(P, "pr1");
        order.verify(prs).save(pr);
    }

    @Test
    void aRevertThatStopsHalfWayRemembersThatItStartedSoRedoStaysBlocked() {
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), eq(true))).thenReturn(ok(true, 1));
        when(rollback.undoChangeSet(eq(P), eq("cs-new"), any(Actor.class), eq(false))).thenReturn(ok(false, 1));
        when(rollback.undoChangeSet(eq(P), eq("cs-old"), any(Actor.class), eq(false))).thenReturn(blocked("changed meanwhile"));

        service.revert(P, "pr1", "owner", "Owner", "Wrong merge order", false);

        assertEquals(DraftPullRequest.Status.APPROVED, pr.getStatus());
        assertTrue(pr.getRevertStartedAt() != null);
    }

    @Test
    void aPreviewNeverMarksTheRevertAsStarted() {
        when(rollback.undoChangeSet(eq(P), anyString(), any(Actor.class), eq(true))).thenReturn(ok(true, 1));

        service.revert(P, "pr1", "owner", "Owner", null, true);

        assertEquals(null, pr.getRevertStartedAt());
    }

    @Test
    void restoringTwiceOnlyAppliesTheChangesOnce() {
        pr.setStatus(DraftPullRequest.Status.REVERTED);

        assertTrue(service.restore(P, "pr1", "author", "Author").ok());
        PrRevertService.Outcome second = service.restore(P, "pr1", "author", "Author");

        assertTrue(second.alreadyDone());
        verify(merges, org.mockito.Mockito.times(1)).apply(anyString(), anyString(), anyString(), any(ExactDelta.class), anyString());
    }

    @Test
    void anAlreadyRevertedPrReportsDoneWithoutTouchingAnything() {
        pr.setStatus(DraftPullRequest.Status.REVERTED);
        PrRevertService.Outcome outcome = service.revert(P, "pr1", "owner", "Owner", "Wrong merge order", false);
        assertTrue(outcome.ok());
        assertTrue(outcome.alreadyDone());
        verify(rollback, never()).undoChangeSet(anyString(), anyString(), any(Actor.class), anyBoolean());
    }

    @Test
    void anOldPrWithoutRecordedChangeSetsIsRefusedWithTheReason() {
        pr.setPublishedChangeSetIds(null);
        PrRevertService.Outcome outcome = service.revert(P, "pr1", "owner", "Owner", null, true);
        assertEquals(409, outcome.status());
        assertTrue(outcome.message().contains("no recorded changes"));
    }

    @Test
    void restoreReplaysTheRecordedStatementsOldestFirstIntoTheAuthorsDraft() {
        pr.setStatus(DraftPullRequest.Status.REVERTED);

        PrRevertService.Outcome outcome = service.restore(P, "pr1", "author", "Author");

        assertTrue(outcome.ok());
        ArgumentCaptor<ExactDelta> delta = ArgumentCaptor.forClass(ExactDelta.class);
        verify(merges).apply(eq(P), eq("author"), eq("Author"), delta.capture(), eq("Restored changes from PR: Add toppings"));
        assertEquals(1, delta.getValue().inserted().size());
        assertTrue(delta.getValue().inserted().iterator().next().getObject().stringValue().endsWith("c"));
        assertEquals(0, delta.getValue().deleted().size());
        assertTrue(pr.getRestoredAt() != null);
    }

    @Test
    void onlyTheAuthorCanRestoreAndOnlyARevertedPr() {
        pr.setStatus(DraftPullRequest.Status.REVERTED);
        assertEquals(403, service.restore(P, "pr1", "someone", "Someone").status());
        pr.setStatus(DraftPullRequest.Status.APPROVED);
        assertEquals(409, service.restore(P, "pr1", "author", "Author").status());
    }

    @Test
    void restoreIsRefusedWhenAChangeHasNoRecordedStatements() {
        pr.setStatus(DraftPullRequest.Status.REVERTED);
        HistoryChange bare = row("x", "cs-new", T0.plusMinutes(5), null, null);
        when(historySync.getChangeSet(P, "cs-new")).thenReturn(List.of(bare));

        PrRevertService.Outcome outcome = service.restore(P, "pr1", "author", "Author");

        assertEquals(409, outcome.status());
        assertTrue(outcome.message().contains("no recorded statements"));
        verify(merges, never()).apply(anyString(), anyString(), anyString(), any(ExactDelta.class), anyString());
    }

    @Test
    void foldingCancelsAStatementAddedThenRemoved() {
        LinkedHashModel deleted = new LinkedHashModel();
        LinkedHashModel inserted = new LinkedHashModel();
        PrRevertService.fold(deleted, inserted, ExactDelta.of(null, "<urn:a> <urn:p> <urn:b> .\n"));
        PrRevertService.fold(deleted, inserted, ExactDelta.of("<urn:a> <urn:p> <urn:b> .\n", null));
        assertTrue(inserted.isEmpty());
        assertTrue(deleted.isEmpty());
    }
}
