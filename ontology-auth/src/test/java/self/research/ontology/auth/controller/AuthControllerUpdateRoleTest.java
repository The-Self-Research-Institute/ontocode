package self.research.ontology.auth.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.ProjectRepository;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;
import self.research.ontology.auth.util.JwtUtil;
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

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerUpdateRoleTest {

    private UserRepository users;
    private JwtUtil jwt;
    private AuthController controller;
    private User mallory;
    private User victim;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        jwt = mock(JwtUtil.class);
        UserDetailsService details = mock(UserDetailsService.class);
        when(details.loadUserByUsername(anyString())).thenReturn(mock(UserDetails.class));
        when(jwt.generateToken(any(), anyString(), any(), any())).thenReturn("signed-jwt");
        controller = new AuthController(mock(AuthenticationManager.class), details, jwt, users,
                mock(WorkspaceRepository.class), mock(ProjectRepository.class), mock(ProjectService.class),
                mock(WorkspaceService.class), mock(StripeService.class), mock(PasswordEncoder.class),
                mock(EmailService.class), mock(RateLimitService.class), mock(AuditService.class),
                mock(SystemSettingsService.class), mock(EnterpriseBypassService.class), mock(IssueReportClient.class),
                new AuthenticatedUsers(users));
        mallory = user("mallory", "mallory@x.org");
        victim = user("victim", "victim@x.org");
        victim.setRoles(Set.of("ROLE_ADMIN"));
        when(users.findByEmail("mallory@x.org")).thenReturn(Optional.of(mallory));
        when(users.findByUsername("mallory")).thenReturn(Optional.of(mallory));
        when(users.findByEmail("victim@x.org")).thenReturn(Optional.of(victim));
        when(users.findByUsername("victim")).thenReturn(Optional.of(victim));
    }

    private static User user(String username, String email) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setRoles(Set.of("ROLE_USER"));
        return user;
    }

    private static Authentication signedInAs(String email) {
        return new UsernamePasswordAuthenticationToken(email, null, AuthorityUtils.createAuthorityList("ROLE_USER"));
    }

    @Test
    void aCallerCannotGetATokenForSomeoneElse() {
        ResponseEntity<?> response = controller.updateRole(
                Map.of("username", "victim", "deploymentType", "cloud"), signedInAs("mallory@x.org"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(jwt, never()).generateToken(any(), anyString(), any(), any());
        assertEquals(Set.of("ROLE_ADMIN"), victim.getRoles());
        verify(users, never()).save(victim);
    }

    @Test
    void aCallerWithNoSignedInIdentityGetsNothing() {
        ResponseEntity<?> response = controller.updateRole(
                Map.of("username", "victim", "deploymentType", "cloud"), null);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(jwt, never()).generateToken(any(), anyString(), any(), any());
    }

    @Test
    void anAnonymousCallerGetsNothing() {
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        ResponseEntity<?> response = controller.updateRole(
                Map.of("username", "victim", "deploymentType", "cloud"), anonymous);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void aSignedInUserCanStillUpdateTheirOwnRoleByUsername() {
        ResponseEntity<?> response = controller.updateRole(
                Map.of("username", "mallory", "deploymentType", "cloud"), signedInAs("mallory@x.org"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("signed-jwt", ((Map<?, ?>) response.getBody()).get("jwt"));
        assertTrue(mallory.getRoles().contains("ROLE_USER"));
    }

    @Test
    void aSignedInUserCanStillUpdateTheirOwnRoleByEmail() {
        ResponseEntity<?> response = controller.updateRole(
                Map.of("username", "MALLORY@x.org", "deploymentType", "cloud"), signedInAs("mallory@x.org"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void aMissingDeploymentTypeIsRejected() {
        ResponseEntity<?> response = controller.updateRole(Map.of("username", "mallory"), signedInAs("mallory@x.org"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(((Map<?, ?>) response.getBody()).containsKey("jwt"));
    }
}
