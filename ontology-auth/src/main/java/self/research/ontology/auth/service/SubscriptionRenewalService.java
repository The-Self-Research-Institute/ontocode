package self.research.ontology.auth.service;

import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.WorkspaceRepository;
import self.research.ontology.auth.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.stripe.exception.StripeException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Service for handling subscription renewals and validity period management
 * 
 * Responsibilities:
 * - Check and update subscription validity periods
 * - Handle auto-renewal when period ends
 * - Track subscription lifecycle (ACTIVE -> EXPIRED -> RENEWED)
 * - Calculate renewal dates based on billing interval (monthly/annual)
 */
@Slf4j
@Service
public class SubscriptionRenewalService {

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StripeService stripeService;

    /**
     * Calculates the next renewal date based on billing interval
     * 
     * @param currentDate Current date/time
     * @param billingInterval "monthly" or "annual"
     * @return Next renewal date
     */
    public LocalDateTime calculateNextRenewalDate(LocalDateTime currentDate, String billingInterval) {
        if ("annual".equalsIgnoreCase(billingInterval)) {
            return currentDate.plus(365, ChronoUnit.DAYS);
        } else {
            // Default to monthly
            return currentDate.plus(30, ChronoUnit.DAYS);
        }
    }

    /**
     * Sets subscription to ACTIVE with calculated renewal date
     * Called after successful payment
     */
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

    /**
     * Checks if subscription is within validity period
     * 
     * @param workspace The workspace to check
     * @return true if within validity period, false if expired
     */
    public boolean isWithinValidityPeriod(Workspace workspace) {
        if (workspace.getSubscriptionCurrentPeriodEnd() == null) {
            return false;
        }
        
        LocalDateTime now = LocalDateTime.now();
        return workspace.getSubscriptionCurrentPeriodEnd().isAfter(now);
    }

    /**
     * Gets remaining days in current billing period
     * 
     * @param workspace The workspace
     * @return Remaining days, or 0 if expired
     */
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

    /**
     * Marks subscription as expired (validity period ended)
     * Does NOT cancel - user can still renew
     */
    public void expireSubscription(Workspace workspace) {
        LocalDateTime now = LocalDateTime.now();
        workspace.setBillingStatus("EXPIRED");
        workspace.setUpdatedAt(now);
        
        workspaceRepository.save(workspace);
        log.info("Marked workspace {} subscription as EXPIRED", workspace.getId());
    }

    /**
     * Cancels subscription (user initiated or billing failed)
     * No more auto-renewal will occur
     */
    public void cancelSubscription(Workspace workspace, String reason) {
        LocalDateTime now = LocalDateTime.now();
        workspace.setBillingStatus("CANCELLED");
        workspace.setSubscriptionEndDate(now);
        workspace.setUpdatedAt(now);

        workspaceRepository.save(workspace);
        log.info("Cancelled subscription for workspace {}, reason: {}", workspace.getId(), reason);
    }

    /**
     * Renews subscription for another period
     * Called when auto-renewal is enabled and previous period ended
     */
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

    /**
     * Scheduled task: Check all workspaces for expired subscriptions
     * Runs every 6 hours to mark expired subscriptions
     */
    @Scheduled(fixedRate = 6 * 60 * 60 * 1000) // 6 hours
    public void checkAndMarkExpiredSubscriptions() {
        log.info("Running subscription expiry check...");
        
        try {
            LocalDateTime now = LocalDateTime.now();
            
            // Find all ACTIVE paid workspaces
            List<Workspace> activeWorkspaces = workspaceRepository.findAll().stream()
                .filter(w -> "ACTIVE".equalsIgnoreCase(w.getBillingStatus()) &&
                           ("PRO".equalsIgnoreCase(w.getSubscriptionPlan()) || 
                            "ENTERPRISE".equalsIgnoreCase(w.getSubscriptionPlan())) &&
                           w.getSubscriptionCurrentPeriodEnd() != null)
                .toList();

            for (Workspace workspace : activeWorkspaces) {
                // If validity period has passed and not yet marked expired
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

    /**
     * Scheduled task: Auto-renew subscriptions where auto-renewal is enabled
     * Runs daily at 2 AM to renew expiring subscriptions
     */
    @Scheduled(cron = "0 0 2 * * ?") // Daily at 2 AM
    public void autoRenewSubscriptions() {
        log.info("Running auto-renewal check...");
        
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime tomorrowStart = now.plus(1, ChronoUnit.DAYS).withHour(0).withMinute(0).withSecond(0);
            
            // Find workspaces that expire within next 24 hours and have auto-renewal enabled
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
                    // Get workspace owner
                    Optional<User> ownerOpt = userRepository.findById(workspace.getOwnerId());
                    if (ownerOpt.isEmpty()) {
                        log.warn("Owner not found for workspace {}", workspace.getId());
                        continue;
                    }

                    User owner = ownerOpt.get();
                    
                    // Check if auto-renew is enabled for the user
                    if (owner.isAutoRenewEnabled() && owner.getStripeSubscriptionId() != null) {
                        renewSubscription(workspace);
                        log.info("Auto-renewed workspace {} for user {}", workspace.getId(), owner.getUsername());
                    } else if (!owner.isAutoRenewEnabled()) {
                        // Auto-renew disabled, mark as expired
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

    /**
     * Handles renewal date calculation for pro-rata charges
     * When user upgrades/downgrades mid-cycle
     */
    public long calculateProRataDays(LocalDateTime currentPeriodEnd) {
        LocalDateTime now = LocalDateTime.now();
        if (currentPeriodEnd == null || currentPeriodEnd.isBefore(now)) {
            return 0;
        }
        return ChronoUnit.DAYS.between(now, currentPeriodEnd);
    }

    /**
     * Calculates pro-rata charge for remaining period
     * @param monthlyPrice The full monthly price
     * @param remainingDays Days remaining in current period
     * @return Pro-rata charge in cents (for Stripe)
     */
    public long calculateProRataCharge(long monthlyPrice, long remainingDays) {
        if (remainingDays <= 0) {
            return 0;
        }
        // Assuming 30-day billing cycle for monthly plans
        return (monthlyPrice * remainingDays) / 30;
    }
}
