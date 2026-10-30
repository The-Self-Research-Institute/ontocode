package self.research.ontology.auth.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import self.research.ontology.auth.model.Project;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.FileMetadataRepository;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.service.EmailService;
import self.research.ontology.auth.service.ProjectService;
import self.research.ontology.auth.service.SystemSettingsService;
import self.research.ontology.auth.service.WorkspaceService;
import self.research.ontology.auth.util.JwtUtil;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectVisibilityUpdateTest {

    private ProjectService projects;
    private ProjectController controller;
    private Project project;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class);
        projects = mock(ProjectService.class);
        controller = new ProjectController(projects, users, mock(FileMetadataRepository.class), mock(JwtUtil.class),
                mock(WorkspaceService.class), mock(self.research.ontology.auth.repository.ProjectRepository.class),
                mock(GridFsTemplate.class), mock(EmailService.class), mock(SystemSettingsService.class),
                mock(MongoTemplate.class));
        project = new Project();
        project.setProjectId("p1");
        project.setWorkspaceId("w1");
        project.setOwnerId("u-owner");
        project.addMember("u-owner", "owner", "owner@x.org", "OWNER");
        workspace = new Workspace();
        workspace.setWorkspaceId("w1");
        when(projects.getProject("p1")).thenReturn(Optional.of(project));
        when(projects.getWorkspace("w1")).thenReturn(Optional.of(workspace));
        user(users, "u-owner", "owner@x.org");
        user(users, "u-other", "other@x.org");
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
    }

    private static void signInAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @Test
    void ownerSwitchingToWorkspaceVisibilityRunsTheShareWithAll() {
        signInAs("owner@x.org");

        ResponseEntity<?> response = controller.updateProject("p1", Map.of("visibility", "WORKSPACE"));

        assertEquals(200, response.getStatusCode().value());
        verify(projects).shareWithAllWorkspaceMembers(project, workspace);
    }

    @Test
    void otherVisibilityValuesAreRejected() {
        signInAs("owner@x.org");

        assertEquals(400, controller.updateProject("p1", Map.of("visibility", "PRIVATE")).getStatusCode().value());
        verify(projects, never()).shareWithAllWorkspaceMembers(any(), any());
    }

    @Test
    void aNonOwnerCannotChangeVisibility() {
        signInAs("other@x.org");

        assertEquals(403, controller.updateProject("p1", Map.of("visibility", "WORKSPACE")).getStatusCode().value());
        verify(projects, never()).shareWithAllWorkspaceMembers(any(), any());
    }
}
