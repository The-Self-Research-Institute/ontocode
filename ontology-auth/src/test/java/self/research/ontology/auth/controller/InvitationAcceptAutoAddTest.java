package self.research.ontology.auth.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvitationAcceptAutoAddTest {

    private InvitationService invitations;
    private ProjectService projectService;
    private InvitationController controller;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        invitations = mock(InvitationService.class);
        UserRepository users = mock(UserRepository.class);
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        projectService = mock(ProjectService.class);
        controller = new InvitationController(invitations, mock(InvitationRepository.class), users, workspaces,
                projectService, mock(SystemSettingsService.class));
        workspace = new Workspace();
        workspace.setWorkspaceId("w1");
        workspace.setOwnerId("u-owner");
        workspace.setMaxMembers(10);
        workspace.addMember("u-owner", "owner", "owner@x.org", Workspace.WorkspaceRole.OWNER);
        when(workspaces.findByWorkspaceId("w1")).thenReturn(Optional.of(workspace));
        User user = new User();
        user.setId("u-new");
        user.setUsername("new");
        user.setEmail("new@x.org");
        when(users.findByEmail("new@x.org")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("new@x.org", null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private Invitation invitation(String role) {
        Invitation inv = new Invitation();
        inv.setInvitationToken("tok");
        inv.setInviteeEmail("new@x.org");
        inv.setWorkspaceId("w1");
        inv.setWorkspaceName("W");
        inv.setStatus("PENDING");
        inv.setRole(role);
        when(invitations.getInvitationByToken("tok")).thenReturn(Optional.of(inv));
        when(invitations.acceptInvitation("tok", "u-new")).thenReturn(inv);
        return inv;
    }

    @Test
    void acceptingAsPendingMemberRunsTheSharedAutoAddWithTheJoinedRole() {
        workspace.addPendingMember("new@x.org", Workspace.WorkspaceRole.MEMBER, "tok");
        invitation("MEMBER");

        assertEquals(200, controller.acceptInvitation("tok").getStatusCode().value());

        verify(projectService).autoAddNewWorkspaceMember(workspace, "u-new", "new", "new@x.org", Workspace.WorkspaceRole.MEMBER);
    }

    @Test
    void acceptingWithoutAPendingEntryAsAdminRunsTheSharedAutoAddAsAdmin() {
        invitation("ADMIN");

        assertEquals(200, controller.acceptInvitation("tok").getStatusCode().value());

        verify(projectService).autoAddNewWorkspaceMember(workspace, "u-new", "new", "new@x.org", Workspace.WorkspaceRole.ADMIN);
    }
}
