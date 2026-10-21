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
                prRepository, ownershipService, draftPublishMergeService,
                new self.research.ontology.owlEditor.service.ProjectAuthorization(mock(self.research.ontology.owlEditor.service.ProjectAccessService.class), true));
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
        return new DraftController.ReviewPRRequest("reviewer1", "looks good");
    }

    @Test
    void approvingDoesNotMarkThePrApprovedWhenApplyingWasConflictBlocked() {
        DraftPullRequest pr = openPr();
        when(prRepository.findById("pr1")).thenReturn(Optional.of(pr));
        DraftTrackingService.ApplyDraftsResult conflictBlocked = new DraftTrackingService.ApplyDraftsResult(
                false, 0, "The shared ontology was updated while you were editing", true, null);
        when(draftTrackingService.applyDrafts(eq("p1"), eq("author1"), eq(false), eq(false), any()))
                .thenReturn(conflictBlocked);

        var response = controller.approvePullRequest("p1", "pr1", reviewRequest(), null);

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

        var response = controller.approvePullRequest("p1", "pr1", reviewRequest(), null);

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

        var response = controller.approvePullRequest("p1", "pr1", reviewRequest(), null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(DraftPullRequest.Status.APPROVED, pr.getStatus());
        assertEquals(3, ((Map<String, Object>) response.getBody()).get("appliedCount"));
    }

    @Test
    void theReviewerIsTheSignedInUserNotTheOneNamedInTheBody() {
        when(ownershipService.canPublishToProject("mallory-id", "p1")).thenReturn(false);

        var response = controller.approvePullRequest("p1", "pr1", reviewRequest(), "mallory-id");

        org.junit.jupiter.api.Assertions.assertEquals(403, response.getStatusCode().value());
        org.mockito.Mockito.verify(prRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void aPublisherIdentifiedByTheTokenCanApprove() {
        DraftPullRequest pr = openPr();
        when(prRepository.findById("pr1")).thenReturn(Optional.of(pr));
        when(ownershipService.canPublishToProject("owner-id", "p1")).thenReturn(true);
        when(draftTrackingService.applyDrafts(eq("p1"), eq("author1"), eq(false), eq(false), any()))
                .thenReturn(new DraftTrackingService.ApplyDraftsResult(true, 2, "ok", false, null));

        var response = controller.approvePullRequest("p1", "pr1", reviewRequest(), "owner-id");

        org.junit.jupiter.api.Assertions.assertEquals(200, response.getStatusCode().value());
        org.junit.jupiter.api.Assertions.assertEquals("owner-id", pr.getReviewerId());
    }

    @Test
    void rejectingAlsoUsesTheSignedInUser() {
        when(ownershipService.canPublishToProject("mallory-id", "p1")).thenReturn(false);

        var response = controller.rejectPullRequest("p1", "pr1", reviewRequest(), "mallory-id");

        org.junit.jupiter.api.Assertions.assertEquals(403, response.getStatusCode().value());
    }
}
