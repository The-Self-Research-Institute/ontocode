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
