package self.research.ontology.auth.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.service.SystemSettingsService;
import self.research.ontology.auth.service.WorkspaceService;
import self.research.ontology.auth.util.JwtUtil;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceOwnerProtectionTest {

    private WorkspaceService workspaces;
    private UserRepository users;
    private Workspace workspace;
    private WorkspaceController controller;

    @BeforeEach
    void setUp() {
        workspaces = mock(WorkspaceService.class);
        users = mock(UserRepository.class);
        controller = new WorkspaceController(workspaces, users, mock(JwtUtil.class), mock(SystemSettingsService.class));

        workspace = new Workspace();
        workspace.setWorkspaceId("w1");
        workspace.setOwnerId("u-owner");
        workspace.addMember("u-owner", "owner", "owner@x.org", Workspace.WorkspaceRole.OWNER);
        workspace.addMember("u-admin", "admin", "admin@x.org", Workspace.WorkspaceRole.ADMIN);
        when(workspaces.getWorkspace("w1")).thenReturn(Optional.of(workspace));
        when(workspaces.getMemberRole("w1", "u-admin")).thenReturn(Workspace.WorkspaceRole.ADMIN);
        when(workspaces.getMemberRole("w1", "u-owner")).thenReturn(Workspace.WorkspaceRole.OWNER);

        user(users, "u-admin", "admin@x.org");
        user(users, "u-owner", "owner@x.org");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void user(UserRepository users, String id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setUsername(id);
        when(users.findByEmail(email)).thenReturn(Optional.of(user));
        when(users.findById(id)).thenReturn(Optional.of(user));
    }

    private static void signInAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @Test
    void anAdminCannotDemoteTheOwner() {
        signInAs("admin@x.org");

        ResponseEntity<?> response = controller.updateMemberRole("w1", "u-owner", Map.of("role", "MEMBER"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(workspaces, never()).updateWorkspace(any(Workspace.class));
    }

    @Test
    void anAdminCannotRemoveTheOwner() {
        signInAs("admin@x.org");

        ResponseEntity<?> response = controller.removeMember("w1", "u-owner");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(workspaces, never()).removeMember(anyString(), anyString());
    }

    @Test
    void theOwnerStillCannotRemoveThemselves() {
        signInAs("owner@x.org");

        ResponseEntity<?> response = controller.removeMember("w1", "u-owner");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    private static boolean membersShowTheirInvitationTokens(ResponseEntity<?> response) {
        List<?> members = (List<?>) ((Map<?, ?>) response.getBody()).get("members");
        return members.stream().anyMatch(member -> ((Map<?, ?>) member).containsKey("invitationToken"));
    }

    @Test
    void anOrdinaryMemberDoesNotSeeInvitationTokens() {
        workspace.addMember("u-viewer", "viewer", "viewer@x.org", Workspace.WorkspaceRole.VIEWER);
        user(users, "u-viewer", "viewer@x.org");
        when(workspaces.hasAccess("w1", "u-viewer")).thenReturn(true);
        signInAs("viewer@x.org");

        assertFalse(membersShowTheirInvitationTokens(controller.getWorkspace("w1")));
    }

    @Test
    void theOwnerSeesInvitationTokens() {
        when(workspaces.hasAccess("w1", "u-owner")).thenReturn(true);
        signInAs("owner@x.org");

        assertTrue(membersShowTheirInvitationTokens(controller.getWorkspace("w1")));
    }
}
