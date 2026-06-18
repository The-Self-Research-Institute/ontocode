package self.research.ontology.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.UserRepository;

/**
 * Grants or revokes Enterprise access for beta / partner users via admin-managed
 * email and domain allowlists (no Stripe subscription required).
 */
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

    /** Apply enterprise bypass on login/signup when the user matches the allowlist. */
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

    /**
     * When a user no longer matches the bypass list, downgrade to FREE unless they
     * have an active paid Stripe subscription.
     */
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

    /** After admin updates allowlists — upgrade matching users and revoke others. */
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

    /**
     * True when the user has ENTERPRISE solely from bypass (no active Stripe sub).
     */
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
