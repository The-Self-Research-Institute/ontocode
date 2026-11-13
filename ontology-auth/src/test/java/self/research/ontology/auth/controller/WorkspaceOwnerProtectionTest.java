package self.research.ontology.auth.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.service.SystemSettingsService;
import self.research.ontology.auth.service.WorkspaceBillingStatusResolver;
import self.research.ontology.auth.service.WorkspaceDtoMapper;
import self.research.ontology.auth.service.WorkspaceService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static self.research.ontology.auth.controller.AuthTestSupport.signInAs;
import static self.research.ontology.auth.controller.AuthTestSupport.stubUserWithId;
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
    private WorkspaceMemberController memberController;

    @BeforeEach
    void setUp() {
        workspaces = mock(WorkspaceService.class);
        users = mock(UserRepository.class);
        SystemSettingsService settings = mock(SystemSettingsService.class);
        WorkspaceDtoMapper mapper = new WorkspaceDtoMapper(new WorkspaceBillingStatusResolver(workspaces, users, settings));
        controller = new WorkspaceController(workspaces, users, settings, mapper);
        memberController = new WorkspaceMemberController(workspaces, users, settings);

        workspace = new Workspace();
        workspace.setWorkspaceId("w1");
        workspace.setOwnerId("u-owner");
        workspace.addMember("u-owner", "owner", "owner@x.org", Workspace.WorkspaceRole.OWNER);
        workspace.addMember("u-admin", "admin", "admin@x.org", Workspace.WorkspaceRole.ADMIN);
        when(workspaces.getWorkspace("w1")).thenReturn(Optional.of(workspace));
        when(workspaces.getMemberRole("w1", "u-admin")).thenReturn(Workspace.WorkspaceRole.ADMIN);
        when(workspaces.getMemberRole("w1", "u-owner")).thenReturn(Workspace.WorkspaceRole.OWNER);

        stubUserWithId(users, "u-admin", "admin@x.org");
        stubUserWithId(users, "u-owner", "owner@x.org");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anAdminCannotDemoteTheOwner() {
        signInAs("admin@x.org");

        ResponseEntity<?> response = memberController.updateMemberRole("w1", "u-owner", Map.of("role", "MEMBER"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(workspaces, never()).updateWorkspace(any(Workspace.class));
    }

    @Test
    void anAdminCannotRemoveTheOwner() {
        signInAs("admin@x.org");

        ResponseEntity<?> response = memberController.removeMember("w1", "u-owner");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(workspaces, never()).removeMember(anyString(), anyString());
    }

    @Test
    void theOwnerStillCannotRemoveThemselves() {
        signInAs("owner@x.org");

        ResponseEntity<?> response = memberController.removeMember("w1", "u-owner");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    private static boolean membersShowTheirInvitationTokens(ResponseEntity<?> response) {
        List<?> members = (List<?>) ((Map<?, ?>) response.getBody()).get("members");
        return members.stream().anyMatch(member -> ((Map<?, ?>) member).containsKey("invitationToken"));
    }

    @Test
    void anOrdinaryMemberDoesNotSeeInvitationTokens() {
        workspace.addMember("u-viewer", "viewer", "viewer@x.org", Workspace.WorkspaceRole.VIEWER);
        stubUserWithId(users, "u-viewer", "viewer@x.org");
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
