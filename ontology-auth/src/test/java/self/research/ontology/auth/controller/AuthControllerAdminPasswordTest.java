package self.research.ontology.auth.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.auth.model.User;
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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerAdminPasswordTest {

    private static final String ADMIN_EMAIL = "admin@ontocode.local";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private UserRepository users;
    private AuthController controller;
    private User admin;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        controller = new AuthController(mock(AuthenticationManager.class), mock(UserDetailsService.class),
                mock(JwtUtil.class), users, mock(WorkspaceRepository.class), mock(ProjectRepository.class),
                mock(ProjectService.class), mock(WorkspaceService.class), mock(StripeService.class), encoder,
                mock(EmailService.class), mock(RateLimitService.class), mock(AuditService.class),
                mock(SystemSettingsService.class), mock(EnterpriseBypassService.class),
                mock(IssueReportClient.class), new AuthenticatedUsers(users));
        ReflectionTestUtils.setField(controller, "adminEmail", ADMIN_EMAIL);
        ReflectionTestUtils.setField(controller, "emailEnabled", false);
        admin = new User();
        admin.setEmail(ADMIN_EMAIL);
        admin.setUsername("admin");
        admin.setRoles(Set.of("ROLE_ADMIN"));
        admin.setSubscriptionPlanName("FREE");
        admin.setPassword(encoder.encode("the-old-shared-password"));
        when(users.findByEmailIgnoreCase(ADMIN_EMAIL)).thenReturn(Optional.of(admin));
        when(users.findAll()).thenReturn(List.of(admin));
    }

    @Test
    void desktopStartReplacesAnOldAdminPasswordWithTheGeneratedOne() {
        ReflectionTestUtils.setField(controller, "desktopMode", true);
        ReflectionTestUtils.setField(controller, "adminPassword", "freshly-generated-secret");

        controller.createDefaultUsers();

        verify(users).save(admin);
        assertTrue(encoder.matches("freshly-generated-secret", admin.getPassword()));
        assertEquals(false, encoder.matches("the-old-shared-password", admin.getPassword()));
    }

    @Test
    void desktopStartLeavesAnAdminPasswordThatAlreadyMatchesAlone() {
        ReflectionTestUtils.setField(controller, "desktopMode", true);
        ReflectionTestUtils.setField(controller, "adminPassword", "the-old-shared-password");

        controller.createDefaultUsers();

        verify(users, never()).save(any());
    }

    @Test
    void aServerStartNeverChangesAnExistingAdminPassword() {
        ReflectionTestUtils.setField(controller, "desktopMode", false);
        ReflectionTestUtils.setField(controller, "adminPassword", "freshly-generated-secret");

        controller.createDefaultUsers();

        verify(users, never()).save(any());
        assertTrue(encoder.matches("the-old-shared-password", admin.getPassword()));
    }

    @Test
    void aBlankConfiguredPasswordNeverChangesAnExistingAdminPassword() {
        ReflectionTestUtils.setField(controller, "desktopMode", true);
        ReflectionTestUtils.setField(controller, "adminPassword", "");

        controller.createDefaultUsers();

        verify(users, never()).save(any());
    }
}
