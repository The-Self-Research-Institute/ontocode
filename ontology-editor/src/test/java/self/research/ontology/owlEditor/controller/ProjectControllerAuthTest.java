package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.document.ProjectDocument;
import self.research.ontology.owlEditor.repository.ProjectRepository;
import self.research.ontology.owlEditor.service.ChangeTrackingService;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.GridFSFileService;
import self.research.ontology.owlEditor.service.ProjectAccessService;
import self.research.ontology.owlEditor.service.ProjectAuthorization;
import self.research.ontology.owlEditor.service.ProjectShareService;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.StorageManager;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectControllerAuthTest {

    private ProjectRepository repository;
    private ProjectAccessService access;
    private ProjectShareService shares;
    private ProjectController controller;

    @BeforeEach
    void setUp() {
        repository = mock(ProjectRepository.class);
        access = mock(ProjectAccessService.class);
        shares = mock(ProjectShareService.class);
        controller = controllerFor(false);
        when(shares.getSharedWithMe(any())).thenReturn(List.of());
    }

    private ProjectController controllerFor(boolean desktop) {
        return new ProjectController(repository, shares, mock(StorageManager.class), mock(GridFSFileService.class),
                mock(SparqlDatasetService.class), mock(DraftTrackingService.class), mock(ChangeTrackingService.class),
                new ProjectAuthorization(access, desktop));
    }

    private static MockHttpServletRequest signedIn(String email, String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"email\":\"" + email + "\",\"userId\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");
        return request;
    }

    private static ProjectDocument file(String id, String ownerEmail, String ownerId) {
        ProjectDocument doc = new ProjectDocument(id, id, id + ".owl");
        doc.setGridfsFileId("g-" + id);
        doc.setOwnerEmail(ownerEmail);
        doc.setOwnerId(ownerId);
        return doc;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> myFiles(ResponseEntity<?> response) {
        return (List<Map<String, Object>>) ((Map<String, Object>) response.getBody()).get("myFiles");
    }

    @Test
    void theListIsForTheSignedInUserNotTheEmailInTheQuery() {
        when(repository.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(
                file("mine", "owner@x.org", "u-owner"), file("victims", "victim@x.org", "u-victim")));

        ResponseEntity<?> response = controller.listProjects("victim@x.org", null, signedIn("owner@x.org", "u-owner"));

        assertEquals(1, myFiles(response).size());
        assertEquals("mine", myFiles(response).get(0).get("id"));
    }

    @Test
    void anOwnerIsRecognisedByUserIdEvenIfTheEmailDiffers() {
        when(repository.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(file("mine", "old@x.org", "u-owner")));

        ResponseEntity<?> response = controller.listProjects(null, null, signedIn("new@x.org", "u-owner"));

        assertEquals(1, myFiles(response).size());
    }

    @Test
    void aFileWithNoOwnerIsNotClaimedByWhoeverAsks() {
        when(repository.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(file("legacy", null, null)));

        ResponseEntity<?> response = controller.listProjects(null, null, signedIn("owner@x.org", "u-owner"));

        assertEquals(0, myFiles(response).size());
        verify(repository, never()).save(any());
    }

    @Test
    void theDesktopAppStillClaimsFilesWithNoOwner() {
        when(repository.findAllByOrderByUpdatedAtDesc()).thenReturn(List.of(file("legacy", null, null)));

        ResponseEntity<?> response = controllerFor(true).listProjects("local@desktop", null, new MockHttpServletRequest());

        assertEquals(1, myFiles(response).size());
        verify(repository).save(any());
    }

    @Test
    void aCallerWithoutATokenGetsNoList() {
        ResponseEntity<?> response = controller.listProjects("victim@x.org", null, new MockHttpServletRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(repository, never()).findAllByOrderByUpdatedAtDesc();
    }

    @Test
    void onlyTheOwnerCanDeleteAProject() {
        when(repository.findById("p1")).thenReturn(Optional.of(file("p1", "owner@x.org", "u-owner")));
        when(access.isOwner("p1", "u-mallory", "mallory@x.org")).thenReturn(false);

        ResponseEntity<?> response = controller.deleteProject("p1", signedIn("mallory@x.org", "u-mallory"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(repository, never()).deleteById(any());
    }

    @Test
    void theOwnerCanDeleteTheirProject() {
        when(repository.findById("p1")).thenReturn(Optional.of(file("p1", "owner@x.org", "u-owner")));
        when(access.isOwner("p1", "u-owner", "owner@x.org")).thenReturn(true);

        ResponseEntity<?> response = controller.deleteProject("p1", signedIn("owner@x.org", "u-owner"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(repository).deleteById("p1");
    }
}
