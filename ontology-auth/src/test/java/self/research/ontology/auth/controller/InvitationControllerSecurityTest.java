package self.research.ontology.auth.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import self.research.ontology.auth.model.Invitation;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.InvitationRepository;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;
import self.research.ontology.auth.service.InvitationService;
import self.research.ontology.auth.service.ProjectService;
import self.research.ontology.auth.service.SystemSettingsService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvitationControllerSecurityTest {

    private InvitationService invitations;
    private UserRepository users;
    private WorkspaceRepository workspaces;
    private InvitationController controller;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        invitations = mock(InvitationService.class);
        users = mock(UserRepository.class);
        workspaces = mock(WorkspaceRepository.class);
        controller = new InvitationController(invitations, mock(InvitationRepository.class), users, workspaces,
                mock(ProjectService.class), mock(SystemSettingsService.class));

        workspace = new Workspace();
        workspace.setWorkspaceId("w1");
        workspace.setOwnerId("u-owner");
        workspace.addMember("u-owner", "owner", "owner@x.org", Workspace.WorkspaceRole.OWNER);
        workspace.addMember("u-viewer", "viewer", "viewer@x.org", Workspace.WorkspaceRole.VIEWER);
        workspace.addMember("u-admin", "admin", "admin@x.org", Workspace.WorkspaceRole.ADMIN);
        when(workspaces.findByWorkspaceId("w1")).thenReturn(Optional.of(workspace));

        user("u-owner", "owner@x.org");
        user("u-viewer", "viewer@x.org");
        user("u-admin", "admin@x.org");
        user("u-mallory", "mallory@x.org");

        Invitation pending = new Invitation();
        pending.setInvitationToken("tok");
        pending.setInviteeEmail("invitee@x.org");
        pending.setWorkspaceId("w1");
        pending.setWorkspaceName("W");
        pending.setStatus("PENDING");
        pending.setRole("MEMBER");
        when(invitations.getInvitationByToken("tok")).thenReturn(Optional.of(pending));
        when(invitations.getPendingInvitations("w1")).thenReturn(List.of(pending));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void user(String id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setUsername(id);
        when(users.findByEmail(email)).thenReturn(Optional.of(user));
    }

    private static void signInAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @Test
    void anInvitationCanOnlyBeAcceptedByTheInvitedAddress() {
        signInAs("mallory@x.org");

        ResponseEntity<?> response = controller.acceptInvitation("tok");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(invitations, never()).acceptInvitation(anyString(), anyString());
    }

    @Test
    void aViewerCannotListThePendingInvitationsAndTheirTokens() {
        signInAs("viewer@x.org");

        assertEquals(HttpStatus.FORBIDDEN, controller.getWorkspaceInvitations("w1").getStatusCode());
    }

    @Test
    void theOwnerAndAnAdminCanListThePendingInvitations() {
        signInAs("owner@x.org");
        assertEquals(HttpStatus.OK, controller.getWorkspaceInvitations("w1").getStatusCode());

        signInAs("admin@x.org");
        assertEquals(HttpStatus.OK, controller.getWorkspaceInvitations("w1").getStatusCode());
    }

    @Test
    void anOutsiderOrAViewerCannotCancelAnInvitation() {
        signInAs("mallory@x.org");
        assertEquals(HttpStatus.FORBIDDEN, controller.cancelInvitation("tok").getStatusCode());

        signInAs("viewer@x.org");
        assertEquals(HttpStatus.FORBIDDEN, controller.cancelInvitation("tok").getStatusCode());

        verify(invitations, never()).cancelInvitation(anyString());
    }

    @Test
    void theOwnerCanCancelAnInvitation() {
        signInAs("owner@x.org");

        assertEquals(HttpStatus.OK, controller.cancelInvitation("tok").getStatusCode());

        verify(invitations).cancelInvitation("tok");
    }
}
