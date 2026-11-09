package self.research.ontology.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.Optional;

@Component
public class WorkspaceBillingStatusResolver {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceBillingStatusResolver.class);

    private final WorkspaceService workspaceService;
    private final UserRepository userRepository;
    private final SystemSettingsService systemSettingsService;

    public WorkspaceBillingStatusResolver(WorkspaceService workspaceService,
                                          UserRepository userRepository,
                                          SystemSettingsService systemSettingsService) {
        this.workspaceService = workspaceService;
        this.userRepository = userRepository;
        this.systemSettingsService = systemSettingsService;
    }

    public String resolve(Workspace workspace) {
        String plan = workspace.getSubscriptionPlan() != null ? workspace.getSubscriptionPlan() : "FREE";

        if ("FREE".equalsIgnoreCase(plan)) {
            return "ACTIVE";
        }

        Optional<User> ownerOpt = userRepository.findById(workspace.getOwnerId());
        if (ownerOpt.isPresent() && systemSettingsService.isEnterpriseBypass(ownerOpt.get().getEmail())) {
            return "ACTIVE";
        }

        String stored = workspace.getBillingStatus();

        if ("ACTIVE".equalsIgnoreCase(stored) || "TRIALING".equalsIgnoreCase(stored)) {
            LocalDateTime periodEnd = workspace.getSubscriptionCurrentPeriodEnd();
            if (periodEnd != null && periodEnd.isBefore(LocalDateTime.now())) {
                workspace.setBillingStatus("EXPIRED");
                workspace.setCollaborationEnabled(false);
                try { workspaceService.updateWorkspace(workspace); } catch (Exception e) {
                    log.error("Failed to persist EXPIRED billing status for workspace {}: {}",
                            workspace.getWorkspaceId(), e.getMessage());
                }
                log.warn("Workspace {} subscription period ended ({}), auto-set to EXPIRED",
                    workspace.getWorkspaceId(), periodEnd);
                return "EXPIRED";
            }
        }

        if (stored != null && !stored.isBlank()) {
            return stored;
        }

        return Boolean.TRUE.equals(workspace.getCollaborationEnabled()) ? "ACTIVE" : "PENDING";
    }
}
