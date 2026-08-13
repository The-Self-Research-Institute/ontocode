package self.research.ontology.auth.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "ontocode.desktop.mode", havingValue = "true")
public class DesktopBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DesktopBootstrap.class);
    private static final String DESKTOP_WORKSPACE_ID = "desktop-workspace-local";

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${ontocode.desktop.user.email:local@ontocode.desktop}")
    private String localEmail;

    @Value("${ontocode.desktop.user.name:Desktop User}")
    private String localName;

    @Value("${ontocode.desktop.user.plan:FREE}")
    private String localPlan;

    public DesktopBootstrap(UserRepository userRepository,
                            WorkspaceRepository workspaceRepository,
                            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        User user = seedUser();
        seedWorkspace(user);
    }

    private User seedUser() {
        Optional<User> existing = userRepository.findByEmail(localEmail);
        User user = existing.orElseGet(User::new);
        user.setEmail(localEmail);
        if (user.getUsername() == null || user.getUsername().isBlank()) {
            user.setUsername(localName != null && !localName.isBlank() ? localName : "Desktop User");
        }
        if (user.getPassword() == null || user.getPassword().isBlank()) {

            user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        }
        user.setEnabled(true);
        if (user.getRoles() == null || user.getRoles().isEmpty()) {
            user.setRoles(Set.of("ROLE_USER"));
        }
        user.setSubscriptionPlanName(localPlan != null ? localPlan.toUpperCase() : "FREE");
        user.setSubscriptionStatus("ACTIVE");
        user.setUpdatedAt(LocalDateTime.now());
        User saved = userRepository.save(user);
        log.info("[DesktopBootstrap] Local user ready: {} (plan {})", localEmail, saved.getSubscriptionPlanName());
        return saved;
    }

    private void seedWorkspace(User user) {
        Workspace ws = workspaceRepository.findByWorkspaceId(DESKTOP_WORKSPACE_ID).orElseGet(Workspace::new);
        ws.setWorkspaceId(DESKTOP_WORKSPACE_ID);
        ws.setOwnerId(user.getId());
        if (ws.getName() == null || ws.getName().isBlank()) {
            ws.setName("Local Desktop Workspace");
        }
        ws.setSubscriptionPlan(user.getSubscriptionPlanName());
        ws.setBillingStatus("ACTIVE");
        ws.setIsDeleted(false);
        ws.addMember(user.getId(), user.getUsername(), user.getEmail(), Workspace.WorkspaceRole.OWNER);
        ws.setUpdatedAt(LocalDateTime.now());
        workspaceRepository.save(ws);
        log.info("[DesktopBootstrap] Local workspace ready: {}", DESKTOP_WORKSPACE_ID);
    }
}
