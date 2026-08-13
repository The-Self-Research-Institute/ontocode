package self.research.ontology.auth.service;

import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.WorkspaceRepository;
import self.research.ontology.auth.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.stripe.exception.StripeException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class SubscriptionRenewalService {

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StripeService stripeService;

    @Value("${billing.expiry-check.enabled:true}")
    private boolean expiryCheckEnabled;

    @Value("${billing.autorenewal.enabled:true}")
    private boolean autoRenewalEnabled;

    public LocalDateTime calculateNextRenewalDate(LocalDateTime currentDate, String billingInterval) {
        if ("annual".equalsIgnoreCase(billingInterval)) {
            return currentDate.plus(365, ChronoUnit.DAYS);
        } else {

            return currentDate.plus(30, ChronoUnit.DAYS);
        }
    }

    public void activateSubscription(Workspace workspace, String billingInterval) {
        LocalDateTime now = LocalDateTime.now();
        workspace.setBillingStatus("ACTIVE");
        workspace.setBillingInterval(billingInterval);
        workspace.setSubscriptionStartDate(now);
        workspace.setSubscriptionCurrentPeriodEnd(calculateNextRenewalDate(now, billingInterval));
        workspace.setUpdatedAt(now);

        workspaceRepository.save(workspace);
        log.info("Activated {} subscription for workspace {}, renewal: {}",
            billingInterval, workspace.getId(), workspace.getSubscriptionCurrentPeriodEnd());
    }

    public boolean isWithinValidityPeriod(Workspace workspace) {
        if (workspace.getSubscriptionCurrentPeriodEnd() == null) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        return workspace.getSubscriptionCurrentPeriodEnd().isAfter(now);
    }

    public long getRemainingDays(Workspace workspace) {
        if (workspace.getSubscriptionCurrentPeriodEnd() == null) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        if (workspace.getSubscriptionCurrentPeriodEnd().isBefore(now)) {
            return 0;
        }

        return ChronoUnit.DAYS.between(now, workspace.getSubscriptionCurrentPeriodEnd());
    }

    public void expireSubscription(Workspace workspace) {
        LocalDateTime now = LocalDateTime.now();
        workspace.setBillingStatus("EXPIRED");
        workspace.setUpdatedAt(now);

        workspaceRepository.save(workspace);
        log.info("Marked workspace {} subscription as EXPIRED", workspace.getId());
    }

    public void cancelSubscription(Workspace workspace, String reason) {
        LocalDateTime now = LocalDateTime.now();
        workspace.setBillingStatus("CANCELLED");
        workspace.setSubscriptionEndDate(now);
        workspace.setUpdatedAt(now);

        workspaceRepository.save(workspace);
        log.info("Cancelled subscription for workspace {}, reason: {}", workspace.getId(), reason);
    }

    public void renewSubscription(Workspace workspace) {
        if (!isWithinValidityPeriod(workspace)) {
            String billingInterval = workspace.getBillingInterval() != null ?
                workspace.getBillingInterval() : "monthly";

            LocalDateTime now = LocalDateTime.now();
            workspace.setSubscriptionCurrentPeriodEnd(calculateNextRenewalDate(now, billingInterval));
            workspace.setBillingStatus("ACTIVE");
            workspace.setUpdatedAt(now);

            workspaceRepository.save(workspace);
            log.info("Renewed {} subscription for workspace {}, new renewal date: {}",
                billingInterval, workspace.getId(), workspace.getSubscriptionCurrentPeriodEnd());
        }
    }

    @Scheduled(fixedRateString = "${billing.expiry-check.interval-ms:21600000}")
    public void checkAndMarkExpiredSubscriptions() {
        if (!expiryCheckEnabled) {
            log.debug("[ExpiryCheck] Disabled — skipping");
            return;
        }
        log.info("Running subscription expiry check...");

        try {
            LocalDateTime now = LocalDateTime.now();

            List<Workspace> activeWorkspaces = workspaceRepository.findAll().stream()
                .filter(w -> "ACTIVE".equalsIgnoreCase(w.getBillingStatus()) &&
                           ("PRO".equalsIgnoreCase(w.getSubscriptionPlan()) ||
                            "ENTERPRISE".equalsIgnoreCase(w.getSubscriptionPlan())) &&
                           w.getSubscriptionCurrentPeriodEnd() != null)
                .toList();

            for (Workspace workspace : activeWorkspaces) {

                if (workspace.getSubscriptionCurrentPeriodEnd().isBefore(now)) {
                    expireSubscription(workspace);
                    log.info("Marked workspace {} as expired", workspace.getId());
                }
            }

            log.info("Subscription expiry check completed. Checked {} workspaces", activeWorkspaces.size());
        } catch (Exception e) {
            log.error("Error in subscription expiry check", e);
        }
    }

    @Scheduled(cron = "${billing.autorenewal.cron:0 0 2 * * ?}")
    public void autoRenewSubscriptions() {
        if (!autoRenewalEnabled) {
            log.debug("[AutoRenewal] Disabled — skipping");
            return;
        }
        log.info("Running auto-renewal check...");

        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime tomorrowStart = now.plus(1, ChronoUnit.DAYS).withHour(0).withMinute(0).withSecond(0);

            List<Workspace> expiringWorkspaces = workspaceRepository.findAll().stream()
                .filter(w -> "ACTIVE".equalsIgnoreCase(w.getBillingStatus()) &&
                           ("PRO".equalsIgnoreCase(w.getSubscriptionPlan()) ||
                            "ENTERPRISE".equalsIgnoreCase(w.getSubscriptionPlan())) &&
                           w.getSubscriptionCurrentPeriodEnd() != null &&
                           w.getSubscriptionCurrentPeriodEnd().isBefore(tomorrowStart) &&
                           w.getSubscriptionCurrentPeriodEnd().isAfter(now))
                .toList();

            for (Workspace workspace : expiringWorkspaces) {
                try {

                    Optional<User> ownerOpt = userRepository.findById(workspace.getOwnerId());
                    if (ownerOpt.isEmpty()) {
                        log.warn("Owner not found for workspace {}", workspace.getId());
                        continue;
                    }

                    User owner = ownerOpt.get();

                    if (owner.isAutoRenewEnabled() && owner.getStripeSubscriptionId() != null) {
                        renewSubscription(workspace);
                        log.info("Auto-renewed workspace {} for user {}", workspace.getId(), owner.getUsername());
                    } else if (!owner.isAutoRenewEnabled()) {

                        expireSubscription(workspace);
                        log.info("Workspace {} marked for expiry (auto-renew disabled)", workspace.getId());
                    }
                } catch (Exception e) {
                    log.error("Error auto-renewing workspace {}: {}", workspace.getId(), e.getMessage());
                }
            }

            log.info("Auto-renewal check completed. Processed {} workspaces", expiringWorkspaces.size());
        } catch (Exception e) {
            log.error("Error in auto-renewal check", e);
        }
    }

    public long calculateProRataDays(LocalDateTime currentPeriodEnd) {
        LocalDateTime now = LocalDateTime.now();
        if (currentPeriodEnd == null || currentPeriodEnd.isBefore(now)) {
            return 0;
        }
        return ChronoUnit.DAYS.between(now, currentPeriodEnd);
    }

    public long calculateProRataCharge(long monthlyPrice, long remainingDays) {
        if (remainingDays <= 0) {
            return 0;
        }

        return (monthlyPrice * remainingDays) / 30;
    }
}
