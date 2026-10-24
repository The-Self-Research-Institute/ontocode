package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.model.DraftPullRequest;
import self.research.ontology.owlEditor.repository.DraftPullRequestRepository;
import self.research.ontology.owlEditor.service.DraftCopyService;
import self.research.ontology.owlEditor.service.DraftPublishMergeService;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DraftControllerTest {

    private final DraftTrackingService draftTrackingService = mock(DraftTrackingService.class);
    private final DraftCopyService draftCopyService = mock(DraftCopyService.class);
    private final ProjectMetadataService metadataService = mock(ProjectMetadataService.class);
    private final DraftPullRequestRepository prRepository = mock(DraftPullRequestRepository.class);
    private final WorkspaceOwnershipService ownershipService = mock(WorkspaceOwnershipService.class);
    private final DraftPublishMergeService draftPublishMergeService = mock(DraftPublishMergeService.class);

    private DraftController controller;

    @BeforeEach
    void setUp() {
        controller = new DraftController(draftTrackingService, draftCopyService, metadataService,
                prRepository, ownershipService, draftPublishMergeService);
        when(ownershipService.canPublishToProject("reviewer1", "p1")).thenReturn(true);
    }

    private DraftPullRequest openPr() {
        DraftPullRequest pr = new DraftPullRequest();
        pr.setId("pr1");
        pr.setProjectId("p1");
        pr.setAuthorId("author1");
        return pr;
    }

    private DraftController.ReviewPRRequest reviewRequest() {
        return new DraftController.ReviewPRRequest("reviewer1", "looks good", null);
    }

    @Test
    void approvingDoesNotMarkThePrApprovedWhenApplyingWasConflictBlocked() {
        DraftPullRequest pr = openPr();
        when(prRepository.findById("pr1")).thenReturn(Optional.of(pr));
        DraftTrackingService.ApplyDraftsResult conflictBlocked = new DraftTrackingService.ApplyDraftsResult(
                false, 0, "The shared ontology was updated while you were editing", true, null);
        when(draftTrackingService.applyDrafts(eq("p1"), eq("author1"), eq(false), eq(false), any()))
                .thenReturn(conflictBlocked);

        var response = controller.approvePullRequest("p1", "pr1", reviewRequest());

        assertEquals(409, response.getStatusCode().value());
        assertEquals(DraftPullRequest.Status.OPEN, pr.getStatus());
    }

    @Test
    void approvingDoesNotMarkThePrApprovedWhenApplyingFailsForAnyOtherReason() {
        DraftPullRequest pr = openPr();
        when(prRepository.findById("pr1")).thenReturn(Optional.of(pr));
        DraftTrackingService.ApplyDraftsResult otherFailure = new DraftTrackingService.ApplyDraftsResult(
                false, 0, "Draft graph copy still in progress — wait before publishing", false, null);
        when(draftTrackingService.applyDrafts(eq("p1"), eq("author1"), eq(false), eq(false), any()))
                .thenReturn(otherFailure);

        var response = controller.approvePullRequest("p1", "pr1", reviewRequest());

        assertEquals(500, response.getStatusCode().value());
        assertEquals(DraftPullRequest.Status.OPEN, pr.getStatus());
        assertEquals("Draft graph copy still in progress — wait before publishing",
                ((Map<String, Object>) response.getBody()).get("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void approvingMarksThePrApprovedWhenApplyingActuallySucceeds() {
        DraftPullRequest pr = openPr();
        when(prRepository.findById("pr1")).thenReturn(Optional.of(pr));
        DraftTrackingService.ApplyDraftsResult success = new DraftTrackingService.ApplyDraftsResult(
                true, 3, "Published draft successfully", false, null);
        when(draftTrackingService.applyDrafts(eq("p1"), eq("author1"), eq(false), eq(false), any()))
                .thenReturn(success);

        var response = controller.approvePullRequest("p1", "pr1", reviewRequest());

        assertEquals(200, response.getStatusCode().value());
        assertEquals(DraftPullRequest.Status.APPROVED, pr.getStatus());
        assertEquals(3, ((Map<String, Object>) response.getBody()).get("appliedCount"));
    }

    private static self.research.ontology.owlEditor.model.DraftChange draftChange(String id) {
        self.research.ontology.owlEditor.model.DraftChange change = new self.research.ontology.owlEditor.model.DraftChange();
        change.setId(id);
        return change;
    }

    private DraftPullRequest frozenPr() {
        DraftPullRequest pr = openPr();
        pr.setTitle("Add toppings");
        pr.setAuthorUsername("pranesh");
        pr.setSnapshotKey("pr-snapshot-pr1");
        pr.setSubmittedChangeIds(java.util.List.of("c1", "c2"));
        pr.setPublishedChangeSetIds(java.util.List.of("cs1"));
        return pr;
    }

    @Test
    void aLegacyPrWhoseDraftGrewStillPublishesTheLiveDraftInsteadOfBeingRefused() {
        DraftPullRequest pr = openPr();
        pr.setSubmittedChangeIds(java.util.List.of("c1", "c2"));
        when(prRepository.findById("pr1")).thenReturn(Optional.of(pr));
        when(draftTrackingService.getUnappliedDraftsForUser("p1", "author1"))
                .thenReturn(java.util.List.of(draftChange("c1"), draftChange("c2"), draftChange("c3")));
        when(draftTrackingService.applyDrafts(eq("p1"), eq("author1"), eq(false), eq(false), any()))
                .thenReturn(new DraftTrackingService.ApplyDraftsResult(true, 3, "ok", false, null));

        assertEquals(200, controller.approvePullRequest("p1", "pr1", reviewRequest()).getStatusCode().value());
        assertEquals(DraftPullRequest.Status.APPROVED, pr.getStatus());
    }

    @Test
    void approvingAFrozenPrPublishesTheFrozenCopyAndThenBringsTheAuthorsDraftUpToDate() throws Exception {
        controller.setSnapshots(mock(self.research.ontology.owlEditor.service.PrSnapshotService.class));
        DraftPullRequest pr = frozenPr();
        when(prRepository.findById("pr1")).thenReturn(Optional.of(pr));
        when(draftTrackingService.applyFrozenPr(eq("p1"), eq("author1"), eq("pr-snapshot-pr1"),
                eq(java.util.List.of("c1", "c2")), eq(java.util.List.of("cs1")), any()))
                .thenReturn(new DraftTrackingService.ApplyDraftsResult(true, 2, "ok", false, null));

        var response = controller.approvePullRequest("p1", "pr1", reviewRequest());

        assertEquals(200, response.getStatusCode().value());
        assertEquals(DraftPullRequest.Status.APPROVED, pr.getStatus());
        org.mockito.Mockito.verify(draftTrackingService, org.mockito.Mockito.never())
                .applyDrafts(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        org.mockito.Mockito.verify(draftPublishMergeService).applyPull(eq("p1"), eq("author1"), any());
        org.mockito.Mockito.verify(draftTrackingService)
                .recordPrPublication("p1", "pr1", "Add toppings", "pranesh", "reviewer1", 2);
    }

    @Test
    void ifCatchingUpTheAuthorsDraftFailsTheMergeStillCounts() throws Exception {
        controller.setSnapshots(mock(self.research.ontology.owlEditor.service.PrSnapshotService.class));
        DraftPullRequest pr = frozenPr();
        when(prRepository.findById("pr1")).thenReturn(Optional.of(pr));
        when(draftTrackingService.applyFrozenPr(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DraftTrackingService.ApplyDraftsResult(true, 2, "ok", false, null));
        when(draftPublishMergeService.applyPull(any(), any(), any())).thenThrow(new IllegalStateException("No draft session"));

        assertEquals(200, controller.approvePullRequest("p1", "pr1", reviewRequest()).getStatusCode().value());
        assertEquals(DraftPullRequest.Status.APPROVED, pr.getStatus());
    }

    @Test
    void aFrozenPrThatNeedsChoicesAsksForThemAndStaysOpen() {
        controller.setSnapshots(mock(self.research.ontology.owlEditor.service.PrSnapshotService.class));
        DraftPullRequest pr = frozenPr();
        when(prRepository.findById("pr1")).thenReturn(Optional.of(pr));
        when(draftTrackingService.applyFrozenPr(any(), any(), any(), any(), any(), any()))
                .thenReturn(new DraftTrackingService.ApplyDraftsResult(false, 0, "choose what to keep", true, null));

        var response = controller.approvePullRequest("p1", "pr1", reviewRequest());

        assertEquals(409, response.getStatusCode().value());
        assertEquals(DraftPullRequest.Status.OPEN, pr.getStatus());
    }

    @Test
    void raisingAPrFreezesTheDraftAndStoresWhatWasFrozen() {
        var snapshots = mock(self.research.ontology.owlEditor.service.PrSnapshotService.class);
        controller.setSnapshots(snapshots);
        when(draftTrackingService.getUnappliedDraftsForUser("p1", "author1"))
                .thenReturn(java.util.List.of(draftChange("c1"), draftChange("c2")));
        when(prRepository.findByProjectIdAndAuthorIdAndStatus("p1", "author1", DraftPullRequest.Status.OPEN))
                .thenReturn(Optional.empty());
        when(prRepository.save(any(DraftPullRequest.class))).thenAnswer(inv -> {
            DraftPullRequest saved = inv.getArgument(0);
            saved.setId("pr9");
            return saved;
        });
        when(snapshots.freeze("p1", "author1", "pr9")).thenReturn(new self.research.ontology.owlEditor.service.PrSnapshotService.Frozen(
                "pr-snapshot-pr9", java.util.List.of("c1"), java.util.List.of("cs1")));

        var response = controller.raisePullRequest("p1", new DraftController.RaisePRRequest("author1", "Author", "T", "D"));

        assertEquals(200, response.getStatusCode().value());
        org.mockito.ArgumentCaptor<DraftPullRequest> saved = org.mockito.ArgumentCaptor.forClass(DraftPullRequest.class);
        org.mockito.Mockito.verify(prRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        DraftPullRequest last = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertEquals("pr-snapshot-pr9", last.getSnapshotKey());
        assertEquals(java.util.List.of("c1"), last.getSubmittedChangeIds());
        assertEquals(java.util.List.of("cs1"), last.getPublishedChangeSetIds());
        assertEquals(1, last.getChangeCount());
    }

    @Test
    void ifTheDraftCannotBeFrozenThePrIsNotRaisedAndTheReasonIsShown() {
        var snapshots = mock(self.research.ontology.owlEditor.service.PrSnapshotService.class);
        controller.setSnapshots(snapshots);
        when(draftTrackingService.getUnappliedDraftsForUser("p1", "author1")).thenReturn(java.util.List.of(draftChange("c1")));
        when(prRepository.findByProjectIdAndAuthorIdAndStatus("p1", "author1", DraftPullRequest.Status.OPEN))
                .thenReturn(Optional.empty());
        when(prRepository.save(any(DraftPullRequest.class))).thenAnswer(inv -> {
            DraftPullRequest saved = inv.getArgument(0);
            saved.setId("pr9");
            return saved;
        });
        when(snapshots.freeze("p1", "author1", "pr9")).thenThrow(new IllegalStateException("Your draft is not ready"));

        var response = controller.raisePullRequest("p1", new DraftController.RaisePRRequest("author1", "Author", "T", "D"));

        assertEquals(409, response.getStatusCode().value());
        org.mockito.Mockito.verify(prRepository).deleteById("pr9");
    }

    @Test
    void rejectingAFrozenPrDropsItsFrozenCopyAndLeavesTheDraft() {
        var snapshots = mock(self.research.ontology.owlEditor.service.PrSnapshotService.class);
        controller.setSnapshots(snapshots);
        DraftPullRequest pr = frozenPr();
        when(prRepository.findById("pr1")).thenReturn(Optional.of(pr));

        var response = controller.rejectPullRequest("p1", "pr1", reviewRequest());

        assertEquals(200, response.getStatusCode().value());
        assertEquals(DraftPullRequest.Status.REJECTED, pr.getStatus());
        org.mockito.Mockito.verify(snapshots).drop("p1", "pr1");
    }

    @Test
    void updatingAnOldPrWithoutACopyFreezesItNow() {
        var snapshots = mock(self.research.ontology.owlEditor.service.PrSnapshotService.class);
        controller.setSnapshots(snapshots);
        DraftPullRequest pr = openPr();
        when(prRepository.findById("pr1")).thenReturn(Optional.of(pr));
        when(snapshots.freeze("p1", "author1", "pr1")).thenReturn(new self.research.ontology.owlEditor.service.PrSnapshotService.Frozen(
                "pr-snapshot-pr1", java.util.List.of("c1", "c2"), java.util.List.of("cs1")));

        var response = controller.updatePullRequest("p1", "pr1", new DraftController.UpdatePRRequest("author1"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("pr-snapshot-pr1", pr.getSnapshotKey());
        assertEquals(java.util.List.of("c1", "c2"), pr.getSubmittedChangeIds());
    }

    @Test
    void updatingAFrozenPrTakesANewFrozenCopy() {
        var snapshots = mock(self.research.ontology.owlEditor.service.PrSnapshotService.class);
        controller.setSnapshots(snapshots);
        DraftPullRequest pr = frozenPr();
        when(prRepository.findById("pr1")).thenReturn(Optional.of(pr));
        when(snapshots.freeze("p1", "author1", "pr1")).thenReturn(new self.research.ontology.owlEditor.service.PrSnapshotService.Frozen(
                "pr-snapshot-pr1", java.util.List.of("c1", "c2", "c3"), java.util.List.of("cs1", "cs2")));

        var response = controller.updatePullRequest("p1", "pr1", new DraftController.UpdatePRRequest("author1"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(java.util.List.of("c1", "c2", "c3"), pr.getSubmittedChangeIds());
        assertEquals(java.util.List.of("cs1", "cs2"), pr.getPublishedChangeSetIds());
        assertEquals(3, pr.getChangeCount());
    }

    @Test
    void approvingGoesAheadWhenTheDraftIsTheSameAsSubmitted() {
        DraftPullRequest pr = openPr();
        pr.setSubmittedChangeIds(java.util.List.of("c1", "c2"));
        when(prRepository.findById("pr1")).thenReturn(Optional.of(pr));
        when(draftTrackingService.getUnappliedDraftsForUser("p1", "author1"))
                .thenReturn(java.util.List.of(draftChange("c2"), draftChange("c1")));
        when(draftTrackingService.applyDrafts(eq("p1"), eq("author1"), eq(false), eq(false), any()))
                .thenReturn(new DraftTrackingService.ApplyDraftsResult(true, 2, "ok", false, null));

        assertEquals(200, controller.approvePullRequest("p1", "pr1", reviewRequest()).getStatusCode().value());
    }

    @Test
    void approvingKeepsTheChangeSetsItPublishedAndRecordsWhoMergedIt() {
        DraftPullRequest pr = openPr();
        pr.setTitle("Add toppings");
        pr.setAuthorUsername("pranesh");
        when(prRepository.findById("pr1")).thenReturn(Optional.of(pr));
        when(draftTrackingService.draftChangeSetIds("p1", "author1")).thenReturn(java.util.List.of("cs1", "cs2"));
        when(draftTrackingService.applyDrafts(eq("p1"), eq("author1"), eq(false), eq(false), any()))
                .thenReturn(new DraftTrackingService.ApplyDraftsResult(true, 3, "ok", false, null));

        controller.approvePullRequest("p1", "pr1", reviewRequest());

        assertEquals(java.util.List.of("cs1", "cs2"), pr.getPublishedChangeSetIds());
        org.mockito.Mockito.verify(draftTrackingService)
                .recordPrPublication("p1", "pr1", "Add toppings", "pranesh", "reviewer1", 3);
    }

    @Test
    void aFailedApprovalRecordsNoPublication() {
        DraftPullRequest pr = openPr();
        when(prRepository.findById("pr1")).thenReturn(Optional.of(pr));
        when(draftTrackingService.applyDrafts(eq("p1"), eq("author1"), eq(false), eq(false), any()))
                .thenReturn(new DraftTrackingService.ApplyDraftsResult(false, 0, "failed", false, null));

        controller.approvePullRequest("p1", "pr1", reviewRequest());

        org.mockito.Mockito.verify(draftTrackingService, org.mockito.Mockito.never())
                .recordPrPublication(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void anOldPrWithoutASubmittedListStillApproves() {
        DraftPullRequest pr = openPr();
        when(prRepository.findById("pr1")).thenReturn(Optional.of(pr));
        when(draftTrackingService.applyDrafts(eq("p1"), eq("author1"), eq(false), eq(false), any()))
                .thenReturn(new DraftTrackingService.ApplyDraftsResult(true, 1, "ok", false, null));

        assertEquals(200, controller.approvePullRequest("p1", "pr1", reviewRequest()).getStatusCode().value());
    }

    @Test
    void theAuthorCanUpdateAnOpenPrToTheCurrentDraft() {
        DraftPullRequest pr = openPr();
        pr.setSubmittedChangeIds(java.util.List.of("c1"));
        when(prRepository.findById("pr1")).thenReturn(Optional.of(pr));
        when(draftTrackingService.getUnappliedDraftsForUser("p1", "author1"))
                .thenReturn(java.util.List.of(draftChange("c1"), draftChange("c2")));

        var response = controller.updatePullRequest("p1", "pr1", new DraftController.UpdatePRRequest("author1"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(java.util.List.of("c1", "c2"), pr.getSubmittedChangeIds());
        assertEquals(2, pr.getChangeCount());
    }

    @Test
    void theListFlagsAnOpenPrWhoseDraftChangedAfterItWasRaised() {
        DraftPullRequest changed = openPr();
        changed.setSubmittedChangeIds(java.util.List.of("c1"));
        DraftPullRequest same = openPr();
        same.setId("pr2");
        same.setSubmittedChangeIds(java.util.List.of("c1", "c2"));
        DraftPullRequest legacy = openPr();
        legacy.setId("pr3");
        when(prRepository.findByProjectIdOrderByCreatedAtDesc("p1")).thenReturn(java.util.List.of(changed, same, legacy));
        when(draftTrackingService.getUnappliedDraftsForUser("p1", "author1"))
                .thenReturn(java.util.List.of(draftChange("c1"), draftChange("c2")));

        controller.listPullRequests("p1", null);

        assertEquals(true, changed.getDraftChanged());
        assertEquals(2, changed.getCurrentChangeCount());
        assertEquals(false, same.getDraftChanged());
        assertEquals(null, legacy.getDraftChanged());
    }

    @Test
    void onlyTheAuthorCanUpdateAPr() {
        when(prRepository.findById("pr1")).thenReturn(Optional.of(openPr()));

        var response = controller.updatePullRequest("p1", "pr1", new DraftController.UpdatePRRequest("someone-else"));

        assertEquals(403, response.getStatusCode().value());
    }

    private static org.springframework.mock.web.MockHttpServletRequest signedInAs(String userId) {
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        String payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"userId\":\"" + userId + "\",\"email\":\"" + userId + "@x.org\"}").getBytes());
        request.addHeader("Authorization", "Bearer h." + payload + ".s");
        return request;
    }

    @Test
    void aDraftOnlyMemberCannotPublishStraightToPublic() {
        self.research.ontology.owlEditor.util.JwtIdentityExtractor.requireSignature(null);

        var response = controller.applyDrafts("p1", "drafter1", false, false, null, signedInAs("drafter1"));

        assertEquals(403, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("pullRequestRequired"));
        org.mockito.Mockito.verify(draftTrackingService, org.mockito.Mockito.never())
                .applyDrafts(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void publishingNeedsASignedInUser() {
        var response = controller.applyDrafts("p1", "reviewer1", false, false, null,
                new org.springframework.mock.web.MockHttpServletRequest());

        assertEquals(401, response.getStatusCode().value());
        org.mockito.Mockito.verify(draftTrackingService, org.mockito.Mockito.never())
                .applyDrafts(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void anEditorPublishesTheirOwnDraftWhateverUserIdTheUrlNames() {
        self.research.ontology.owlEditor.util.JwtIdentityExtractor.requireSignature(null);
        when(draftTrackingService.applyDrafts(eq("p1"), eq("reviewer1"), eq(false), eq(false), any()))
                .thenReturn(new DraftTrackingService.ApplyDraftsResult(true, 2, "Published draft successfully", false, null));

        var response = controller.applyDrafts("p1", "someone-else", false, false, null, signedInAs("reviewer1"));

        assertEquals(200, response.getStatusCode().value());
        org.mockito.Mockito.verify(draftTrackingService).applyDrafts(eq("p1"), eq("reviewer1"), eq(false), eq(false), any());
        org.mockito.Mockito.verify(draftTrackingService, org.mockito.Mockito.never())
                .applyDrafts(eq("p1"), eq("someone-else"), org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.anyBoolean(), any());
    }
}
