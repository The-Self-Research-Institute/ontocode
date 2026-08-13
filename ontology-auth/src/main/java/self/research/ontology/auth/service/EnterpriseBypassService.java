package self.research.ontology.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.UserRepository;

@Service
public class EnterpriseBypassService {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseBypassService.class);

    private final SystemSettingsService systemSettingsService;
    private final UserRepository userRepository;
    private final WorkspaceService workspaceService;

    public EnterpriseBypassService(SystemSettingsService systemSettingsService,
                                   UserRepository userRepository,
                                   WorkspaceService workspaceService) {
        this.systemSettingsService = systemSettingsService;
        this.userRepository = userRepository;
        this.workspaceService = workspaceService;
    }

    public boolean isBypassed(String email) {
        return systemSettingsService.isEnterpriseBypass(email);
    }

    public void applyBypassIfEligible(User user) {
        if (user == null || user.getEmail() == null) return;
        if (!isBypassed(user.getEmail())) return;

        boolean dirty = false;
        if (!"ENTERPRISE".equalsIgnoreCase(user.getSubscriptionPlanName())) {
            user.setSubscriptionPlanName("ENTERPRISE");
            dirty = true;
        }
        if (!"active".equalsIgnoreCase(user.getSubscriptionStatus())) {
            user.setSubscriptionStatus("active");
            dirty = true;
        }
        if (dirty) {
            userRepository.save(user);
            log.info("Enterprise bypass: granted ENTERPRISE to {}", user.getEmail());
        }
        workspaceService.syncWorkspacesToOwnerPlan(user);
    }

    public void revokeBypassIfNeeded(User user) {
        if (user == null || user.getEmail() == null) return;
        if (isBypassed(user.getEmail())) return;
        if (!isBypassOnlyEnterprise(user)) return;

        user.setSubscriptionPlanName("FREE");
        user.setSubscriptionStatus("active");
        userRepository.save(user);
        workspaceService.syncWorkspacesToOwnerPlan(user);
        log.info("Enterprise bypass revoked: {} downgraded to FREE", user.getEmail());
    }

    public void reconcileAllUsers() {
        userRepository.findAll().forEach(user -> {
            if (user.getEmail() == null || user.getEmail().isBlank()) return;
            if (isBypassed(user.getEmail())) {
                applyBypassIfEligible(user);
            } else {
                revokeBypassIfNeeded(user);
            }
        });
    }

    public boolean isBypassOnlyEnterprise(User user) {
        if (user == null) return false;
        String plan = user.getSubscriptionPlanName();
        if (plan == null || !"ENTERPRISE".equalsIgnoreCase(plan)) return false;
        return !hasActivePaidStripeSubscription(user);
    }

    private boolean hasActivePaidStripeSubscription(User user) {
        String subId = user.getStripeSubscriptionId();
        if (subId == null || subId.isBlank()) return false;
        String status = user.getSubscriptionStatus();
        return "active".equalsIgnoreCase(status) || "trialing".equalsIgnoreCase(status);
    }
}
