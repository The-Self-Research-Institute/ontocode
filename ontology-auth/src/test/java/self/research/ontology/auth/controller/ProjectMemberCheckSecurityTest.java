package self.research.ontology.auth.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import self.research.ontology.auth.model.Project;
import self.research.ontology.auth.model.User;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectMemberCheckSecurityTest {

    private ProjectController controller;

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class);
        ProjectService projects = mock(ProjectService.class);
        controller = new ProjectController(projects, users, mock(FileMetadataRepository.class), mock(JwtUtil.class),
                mock(WorkspaceService.class), mock(self.research.ontology.auth.repository.ProjectRepository.class),
                mock(GridFsTemplate.class), mock(EmailService.class), mock(SystemSettingsService.class),
                mock(MongoTemplate.class));

        Project project = new Project();
        project.addMember("u-member", "member", "member@x.org", "MEMBER");
        when(projects.getProject("p1")).thenReturn(Optional.of(project));
        user(users, "u-member", "member@x.org");
        user(users, "u-mallory", "mallory@x.org");
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
    void aStrangerCannotAskWhoIsAMemberOfAProject() {
        signInAs("mallory@x.org");

        ResponseEntity<?> response = controller.checkMemberExists("p1", "member@x.org");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void aMemberCanCheckWhetherAnEmailIsAlreadyAMember() {
        signInAs("member@x.org");

        ResponseEntity<?> response = controller.checkMemberExists("p1", "member@x.org");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue((Boolean) ((Map<?, ?>) response.getBody()).get("exists"));
    }
}
