package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.document.ProjectShare;
import self.research.ontology.owlEditor.service.ProjectAccessService;
import self.research.ontology.owlEditor.service.ProjectAuthorization;
import self.research.ontology.owlEditor.service.ProjectShareService;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectShareControllerAuthTest {

    private ProjectShareService shares;
    private ProjectAccessService access;
    private ProjectShareController controller;

    @BeforeEach
    void setUp() {
        shares = mock(ProjectShareService.class);
        access = mock(ProjectAccessService.class);
        controller = new ProjectShareController(shares, new ProjectAuthorization(access, false));
        when(access.isOwner("p1", "u-owner", "owner@x.org")).thenReturn(true);
    }

    private static MockHttpServletRequest signedIn(String email, String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"email\":\"" + email + "\",\"userId\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer h." + payload + ".s");
        return request;
    }

    private static MockHttpServletRequest mallory() {
        return signedIn("mallory@x.org", "u-mallory");
    }

    private static MockHttpServletRequest owner() {
        return signedIn("owner@x.org", "u-owner");
    }

    @Test
    void aNonOwnerCannotCreateAShareEvenByNamingTheOwner() {
        var response = controller.createShare(Map.of("projectId", "p1", "ownerEmail", "owner@x.org"), mallory());

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(shares, never()).createShare(anyString(), anyString(), anyString());
    }

    @Test
    void theOwnerOfTheShareIsTheSignedInUserNotTheOneInTheBody() {
        ProjectShare created = new ProjectShare();
        created.setShareLink("abc");
        when(shares.createShare("p1", "owner@x.org", "view")).thenReturn(created);

        var response = controller.createShare(Map.of("projectId", "p1", "ownerEmail", "someone-else@x.org"), owner());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(shares).createShare("p1", "owner@x.org", "view");
    }

    @Test
    void aNonOwnerCannotAddOrRemoveAccess() {
        var add = controller.addEmailAccess(Map.of("projectId", "p1", "email", "mallory@x.org"), mallory());
        var remove = controller.removeEmailAccess(Map.of("projectId", "p1", "email", "owner@x.org"), mallory());

        assertEquals(HttpStatus.FORBIDDEN, add.getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, remove.getStatusCode());
        verify(shares, never()).addEmailAccess(anyString(), anyString(), anyString());
        verify(shares, never()).removeEmailAccess(anyString(), anyString());
    }

    @Test
    void aNonOwnerCannotReadOrDeleteAShare() {
        var read = controller.getProjectShare("p1", mallory());
        var delete = controller.deleteShare("p1", mallory());

        assertEquals(HttpStatus.FORBIDDEN, read.getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, delete.getStatusCode());
        verify(shares, never()).deleteShare(anyString());
        verify(shares, never()).getShareByProjectId(anyString());
    }

    @Test
    void theOwnerCanReadAndDeleteTheShare() {
        when(shares.getShareByProjectId("p1")).thenReturn(Optional.of(new ProjectShare()));

        assertEquals(HttpStatus.OK, controller.getProjectShare("p1", owner()).getStatusCode());
        assertEquals(HttpStatus.OK, controller.deleteShare("p1", owner()).getStatusCode());
        verify(shares).deleteShare("p1");
    }

    @Test
    void myAndSharedWithMeUseTheSignedInEmailNotTheOneInTheQuery() {
        when(shares.getMyShares("owner@x.org")).thenReturn(List.of());
        when(shares.getSharedWithMe("owner@x.org")).thenReturn(List.of());

        controller.getMyShares("victim@x.org", owner());
        controller.getSharedWithMe("victim@x.org", owner());

        verify(shares).getMyShares("owner@x.org");
        verify(shares).getSharedWithMe("owner@x.org");
        verify(shares, never()).getMyShares("victim@x.org");
    }

    @Test
    void anUnauthenticatedCallerGetsNothing() {
        MockHttpServletRequest anonymous = new MockHttpServletRequest();

        assertEquals(HttpStatus.UNAUTHORIZED, controller.createShare(Map.of("projectId", "p1"), anonymous).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.getMyShares("victim@x.org", anonymous).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.deleteShare("p1", anonymous).getStatusCode());
        verify(shares, never()).getMyShares(any());
    }

    @Test
    void theDesktopAppNeedsNoSignIn() {
        controller = new ProjectShareController(shares, new ProjectAuthorization(access, true));
        when(shares.getShareByProjectId("p1")).thenReturn(Optional.of(new ProjectShare()));

        assertEquals(HttpStatus.OK, controller.getProjectShare("p1", new MockHttpServletRequest()).getStatusCode());
    }
}
