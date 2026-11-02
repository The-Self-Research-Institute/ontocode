package self.research.ontology.auth.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import self.research.ontology.auth.model.Project;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.ProjectRepository;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;
import self.research.ontology.auth.service.AuditService;
import self.research.ontology.auth.service.AuthenticatedUsers;
import self.research.ontology.auth.service.EmailService;
import self.research.ontology.auth.service.EnterpriseBypassService;
import self.research.ontology.auth.service.IssueReportClient;
import self.research.ontology.auth.service.ProjectService;
import self.research.ontology.auth.service.RateLimitService;
import self.research.ontology.auth.service.StripeService;
import self.research.ontology.auth.service.SystemSettingsService;
import self.research.ontology.auth.service.WorkspaceService;
import self.research.ontology.auth.util.JwtUtil;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerDeleteAccountTest {

    private UserRepository users;
    private ProjectRepository projects;
    private WorkspaceRepository workspaces;
    private ProjectService projectService;
    private WorkspaceService workspaceService;
    private AuthController controller;
    private User user;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        projects = mock(ProjectRepository.class);
        workspaces = mock(WorkspaceRepository.class);
        projectService = mock(ProjectService.class);
        workspaceService = mock(WorkspaceService.class);
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.extractEmail("t")).thenReturn("u@x.org");
        controller = new AuthController(mock(AuthenticationManager.class), mock(UserDetailsService.class), jwt, users,
                workspaces, projects, projectService, workspaceService, mock(StripeService.class),
                mock(PasswordEncoder.class), mock(EmailService.class), mock(RateLimitService.class),
                mock(AuditService.class), mock(SystemSettingsService.class), mock(EnterpriseBypassService.class),
                mock(IssueReportClient.class), new AuthenticatedUsers(users));
        user = new User();
        user.setId("u1");
        user.setEmail("u@x.org");
        user.setUsername("u");
        when(users.findByEmail("u@x.org")).thenReturn(Optional.of(user));
        when(workspaces.findByOwnerId("u1")).thenReturn(List.of());
        when(projects.findByMembers_UserId("u1")).thenReturn(List.of());
        when(workspaces.findByMembers_UserId("u1")).thenReturn(List.of());
    }

    private static Project project(String projectId, String workspaceId) {
        Project p = new Project();
        p.setProjectId(projectId);
        p.setOwnerId("u1");
        p.setWorkspaceId(workspaceId);
        return p;
    }

    private static Workspace workspace(String workspaceId) {
        Workspace w = new Workspace();
        w.setWorkspaceId(workspaceId);
        w.setOwnerId("u1");
        return w;
    }

    @Test
    void editorPerFileDocumentsAreSkipped() {
        when(projects.findByOwnerId("u1")).thenReturn(List.of(
                project("proj-1", "ws-1"), project("proj-1--file-a", "ws-1"), project(null, "ws-1")));

        ResponseEntity<?> response = controller.deleteAccount("Bearer t", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(projectService).hardDeleteProjectCompletely("proj-1", "u1");
        verify(projectService, never()).hardDeleteProjectCompletely("proj-1--file-a", "u1");
        verify(users).delete(user);
    }

    @Test
    void impactListingHidesEditorPerFileDocuments() {
        when(projects.findByOwnerId("u1")).thenReturn(List.of(
                project("proj-1", "ws-1"), project("proj-1--file-a", "ws-1")));

        ResponseEntity<?> response = controller.getAccountDeletionImpact("Bearer t");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listed = (List<Map<String, Object>>) ((Map<?, ?>) response.getBody()).get("projects");
        assertEquals(1, listed.size());
        assertEquals("proj-1", listed.get(0).get("projectId"));
    }

    @Test
    void oneFailingProjectDoesNotAbortAccountDeletion() {
        when(projects.findByOwnerId("u1")).thenReturn(List.of(project("proj-1", "ws-1"), project("proj-2", "ws-1")));
        doThrow(new SecurityException("Only the project owner can permanently delete this project"))
                .when(projectService).hardDeleteProjectCompletely("proj-1", "u1");

        ResponseEntity<?> response = controller.deleteAccount("Bearer t", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(projectService).hardDeleteProjectCompletely("proj-2", "u1");
        verify(users).delete(user);
    }

    @Test
    void workspaceIsNotRemovedBeforeProjectsAreHandled() {
        when(workspaces.findByOwnerId("u1")).thenReturn(List.of(workspace("ws-1")));
        when(projects.findByOwnerId("u1")).thenReturn(List.of(project("proj-1", "ws-2")));

        controller.deleteAccount("Bearer t", null);

        InOrder order = inOrder(projectService, workspaceService);
        order.verify(projectService).hardDeleteProjectCompletely("proj-1", "u1");
        order.verify(workspaceService).hardDeleteWorkspaceCompletely("ws-1", "u1");
    }

    @Test
    void retryAfterPartialFailureCompletes() {
        when(workspaces.findByOwnerId("u1")).thenReturn(List.of(workspace("ws-1")));
        when(projects.findByOwnerId("u1")).thenReturn(List.of(project("proj-1", "ws-2")));
        doThrow(new IllegalStateException("boom")).when(workspaceService).hardDeleteWorkspaceCompletely("ws-1", "u1");

        ResponseEntity<?> first = controller.deleteAccount("Bearer t", null);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, first.getStatusCode());
        verify(users, never()).delete(user);

        reset(workspaceService);
        when(projects.findByOwnerId("u1")).thenReturn(List.of());

        ResponseEntity<?> second = controller.deleteAccount("Bearer t", null);

        assertEquals(HttpStatus.OK, second.getStatusCode());
        verify(workspaceService).hardDeleteWorkspaceCompletely("ws-1", "u1");
        verify(users).delete(user);
    }
}
