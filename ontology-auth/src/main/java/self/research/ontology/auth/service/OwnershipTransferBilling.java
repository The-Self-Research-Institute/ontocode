package self.research.ontology.auth.service;

import java.time.LocalDateTime;
import java.util.Optional;

public final class OwnershipTransferBilling {

    private OwnershipTransferBilling() {
    }

    public static Optional<String> blockedReason(
            String workspacePlan,
            boolean targetEnterpriseBypass,
            String targetName,
            String targetPlan,
            String targetStatus,
            LocalDateTime targetPeriodEnd,
            LocalDateTime now) {
        if (isFree(workspacePlan) || targetEnterpriseBypass) {
            return Optional.empty();
        }
        String who = targetName == null || targetName.isBlank() ? "This member" : targetName;
        String plan = workspacePlan.trim().toUpperCase();
        if (isFree(targetPlan)) {
            return Optional.of(who + " does not have a paid plan. This workspace is on the " + plan
                    + " plan and would lock for everyone. Ask them to subscribe first, then transfer ownership.");
        }
        boolean activeStatus = "active".equalsIgnoreCase(targetStatus) || "trialing".equalsIgnoreCase(targetStatus);
        if (!activeStatus) {
            return Optional.of(who + "'s plan is not active (" + (targetStatus == null ? "no status" : targetStatus)
                    + "). The workspace would lock for everyone. Ask them to renew first, then transfer ownership.");
        }
        if (targetPeriodEnd != null && targetPeriodEnd.isBefore(now)) {
            return Optional.of(who + "'s plan period has ended. The workspace would lock for everyone. "
                    + "Ask them to renew first, then transfer ownership.");
        }
        return Optional.empty();
    }

    private static boolean isFree(String plan) {
        return plan == null || plan.isBlank() || "FREE".equalsIgnoreCase(plan.trim());
    }
}
