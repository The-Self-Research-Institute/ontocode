package self.research.ontology.auth.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.model.PlanFeatureConfig;
import self.research.ontology.auth.service.PlanFeatureConfigService;
import self.research.ontology.auth.service.StripeService;
import self.research.ontology.auth.service.SystemSettingsService;
import self.research.ontology.auth.service.WorkspaceService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("/api/billing")
public class SubscriptionController {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionController.class);

    private final StripeService stripeService;
    private final UserRepository userRepository;
    private final WorkspaceService workspaceService;
    private final PlanFeatureConfigService planFeatureConfigService;
    private final SystemSettingsService systemSettingsService;

    @Value("${stripe.trial-period-days:14}")
    private Long trialPeriodDays;

    public SubscriptionController(StripeService stripeService, UserRepository userRepository,
                                  WorkspaceService workspaceService, PlanFeatureConfigService planFeatureConfigService,
                                  SystemSettingsService systemSettingsService) {
        this.stripeService = stripeService;
        this.userRepository = userRepository;
        this.workspaceService = workspaceService;
        this.planFeatureConfigService = planFeatureConfigService;
        this.systemSettingsService = systemSettingsService;
    }

    @GetMapping("/plans")
    public ResponseEntity<?> getPlans() {
        var configs = planFeatureConfigService.getAllByPlanId();
        var plans = List.of("FREE", "PRO", "ENTERPRISE").stream()
            .map(id -> buildPlanResponse(configs.get(id)))
            .toList();
        return ResponseEntity.ok(Map.of(
            "plans", plans,
            "trialPeriodDays", trialPeriodDays
        ));
    }

    private Map<String, Object> buildPlanResponse(PlanFeatureConfig config) {
        if (config == null) return Map.of();
        return Map.of(
            "id",                   config.getPlanId(),
            "monthlyPrice",         config.getMonthlyPrice(),
            "annualDiscountPercent", config.getAnnualDiscountPercent(),
            "annualPrice",          config.computedAnnualPrice(),
            "features",             config.getFeatures(),
            "limitations",          config.getLimitations(),
            "maxMembers",           config.getMaxMembers()
        );
    }

    @GetMapping("/public-config")
    public ResponseEntity<?> getPublicConfig() {
        return ResponseEntity.ok(Map.of("stripePublishableKey", stripeService.getPublishableKey()));
    }

    @GetMapping("/subscription")
    public ResponseEntity<?> getSubscription(@AuthenticationPrincipal UserDetails principal) {
        User user = resolveUser(principal);
        if (systemSettingsService.isEnterpriseBypass(user.getEmail())) {
            return ResponseEntity.ok(Map.of(
                "planName",               "ENTERPRISE",
                "status",                 "active",
                "billingInterval",        "monthly",
                "autoRenewEnabled",       true,
                "currentPeriodEnd",       "",
                "canceledAt",             "",
                "hasStripeCustomer",      user.getStripeCustomerId() != null,
                "hasUsedFreeTrial",       user.isHasUsedFreeTrial(),
                "trialEligible",          false,
                "enterpriseDomainBypass", true
            ));
        }

        String liveStatus = stripeService.syncStatusFromStripe(user);
        return ResponseEntity.ok(Map.ofEntries(
                Map.entry("planName",               orEmpty(user.getSubscriptionPlanName())),
                Map.entry("status",                 liveStatus),
                Map.entry("billingInterval",        orEmpty(user.getBillingInterval())),
                Map.entry("autoRenewEnabled",       user.isAutoRenewEnabled()),
                Map.entry("currentPeriodEnd",       user.getSubscriptionCurrentPeriodEnd() != null
                                                        ? user.getSubscriptionCurrentPeriodEnd().toString() : ""),
                Map.entry("canceledAt",             user.getSubscriptionCanceledAt() != null
                                                        ? user.getSubscriptionCanceledAt().toString() : ""),
                Map.entry("hasStripeCustomer",      user.getStripeCustomerId() != null),
                Map.entry("hasUsedFreeTrial",       user.isHasUsedFreeTrial()),
                Map.entry("trialEligible",          !user.isHasUsedFreeTrial()
                                                        && user.getFirstSubscriptionAt() == null
                                                        && (user.getStripeSubscriptionId() == null || user.getStripeSubscriptionId().isBlank())),
                Map.entry("enterpriseDomainBypass", false),
                Map.entry("pendingBillingInterval",     user.getPendingBillingInterval() != null ? user.getPendingBillingInterval() : ""),
                Map.entry("pendingBillingIntervalDate", user.getPendingBillingIntervalDate() != null ? user.getPendingBillingIntervalDate().toString() : "")
        ));
    }

    @GetMapping("/subscription/details")
    public ResponseEntity<?> getSubscriptionDetails(@AuthenticationPrincipal UserDetails principal) {
        try {
            User user = resolveUser(principal);
            return ResponseEntity.ok(stripeService.getBillingSummary(user));
        } catch (Exception e) {
            log.error("Failed to load billing summary for {}: {}", principal.getUsername(), e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to load billing summary"));
        }
    }

    @PostMapping("/checkout")
    public ResponseEntity<?> createCheckout(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody Map<String, String> body) {
        String planName = body.get("planName");
        String interval = body.get("interval");
        String workspaceId = body.get("workspaceId");

        if (planName == null || interval == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "planName and interval are required"));
        }
        if (!planName.matches("^(PRO|ENTERPRISE)$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid planName. Must be PRO or ENTERPRISE"));
        }
        if (!interval.matches("^(monthly|yearly|annual)$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid interval. Must be monthly, yearly, or annual"));
        }

        String normalizedInterval = "annual".equalsIgnoreCase(interval) ? "yearly" : interval.toLowerCase();

        try {
            User user = resolveUser(principal);
            if (workspaceId != null && !workspaceId.isBlank()) {
                Workspace workspace = workspaceService.getWorkspace(workspaceId)
                        .orElseThrow(() -> new IllegalStateException("Workspace not found"));
                if (!workspaceService.hasAccess(workspaceId, user.getId())) {
                    return ResponseEntity.status(403).body(Map.of("error", "You don't have access to this workspace"));
                }
            }
            String clientSecret = stripeService.createCheckoutSession(user, planName, normalizedInterval, workspaceId);
            return ResponseEntity.ok(Map.of("clientSecret", clientSecret));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Checkout session creation failed for {}: {}", principal.getUsername(), e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to create checkout session"));
        }
    }

    @PostMapping("/setup")
    public ResponseEntity<?> createSetup(@AuthenticationPrincipal UserDetails principal) {
        try {
            User user = resolveUser(principal);
            String clientSecret = stripeService.createSetupIntent(user);
            return ResponseEntity.ok(Map.of(
                    "clientSecret", clientSecret,
                    "stripePublishableKey", stripeService.getPublishableKey(),
                    "hasUsedFreeTrial", user.isHasUsedFreeTrial(),
                    "trialEligible", !user.isHasUsedFreeTrial()
                            && user.getFirstSubscriptionAt() == null
                            && (user.getStripeSubscriptionId() == null || user.getStripeSubscriptionId().isBlank())
            ));
        } catch (Exception e) {
            log.error("Setup intent creation failed for {}: {}", principal.getUsername(), e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to create payment setup"));
        }
    }

    @PostMapping("/subscribe")
    public ResponseEntity<?> subscribe(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody Map<String, String> body) {
        String setupIntentId = body.get("setupIntentId");
        String planName = body.get("planName");
        String interval = body.get("interval");
        String workspaceId = body.get("workspaceId");

        if (planName != null) {
            planName = planName.trim().toUpperCase();
        }
        if (interval != null) {
            interval = interval.trim();
        }

        if (setupIntentId == null || planName == null || interval == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "setupIntentId, planName, and interval are required"));
        }
        if (!planName.matches("^(PRO|ENTERPRISE)$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid planName. Must be PRO or ENTERPRISE"));
        }
        if (!interval.matches("^(monthly|yearly|annual)$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid interval. Must be monthly, yearly, or annual"));
        }

        String normalizedInterval = "annual".equalsIgnoreCase(interval) ? "yearly" : interval.toLowerCase();

        try {
            User user = resolveUser(principal);
            if (workspaceId != null && !workspaceId.isBlank()) {
                if (workspaceService.getWorkspace(workspaceId).isEmpty()) {
                    return ResponseEntity.status(404).body(Map.of("error", "Workspace not found"));
                }
                if (!workspaceService.hasAccess(workspaceId, user.getId())) {
                    return ResponseEntity.status(403).body(Map.of("error", "You don't have access to this workspace"));
                }
            }
            String subscriptionId = stripeService.createSubscriptionAfterSetup(user, setupIntentId, planName, normalizedInterval, workspaceId);
            return ResponseEntity.ok(Map.of("subscriptionId", subscriptionId));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Subscribe failed for {}: {}", principal.getUsername(), e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to create subscription"));
        }
    }

    @PostMapping("/update-payment-method")
    public ResponseEntity<?> updatePaymentMethod(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody Map<String, String> body) {
        String setupIntentId = body.get("setupIntentId");
        String workspaceId = body.get("workspaceId");
        if (setupIntentId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "setupIntentId is required"));
        }
        try {
            User user = resolveUser(principal);
            stripeService.updateDefaultPaymentMethod(user, setupIntentId, workspaceId);
            return ResponseEntity.ok(Map.of("message", "Payment method updated successfully."));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Update payment method failed for {}: {}", principal.getUsername(), e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to update payment method"));
        }
    }

    @PostMapping("/cancel-workspace")
    public ResponseEntity<?> cancelWorkspaceSubscription(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody Map<String, String> body) {
        String workspaceId = body.get("workspaceId");
        try {
            User user = resolveUser(principal);

            if (workspaceId == null || workspaceId.isBlank()) {
                stripeService.cancelAccountSubscription(user);
                return ResponseEntity.ok(Map.of(
                        "message", "Auto-renewal disabled. Your subscription remains active until the end of the current billing period."));
            }

            Workspace workspace = workspaceService.getWorkspace(workspaceId)
                    .orElse(null);
            if (workspace == null) {
                return ResponseEntity.status(404).body(Map.of("error", "Workspace not found"));
            }
            if (!user.getId().equals(workspace.getOwnerId())) {
                return ResponseEntity.status(403).body(Map.of(
                        "error", "Only the workspace owner can cancel the subscription."));
            }
            stripeService.cancelWorkspaceSubscription(user, workspaceId);
            return ResponseEntity.ok(Map.of("message", "Subscription canceled successfully."));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Cancel subscription failed for {}: {}", principal.getUsername(), e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to cancel subscription"));
        }
    }

    @PostMapping("/portal")
    public ResponseEntity<?> createPortalSession(@AuthenticationPrincipal UserDetails principal) {
        try {
            User user = resolveUser(principal);
            String portalUrl = stripeService.createBillingPortalSession(user);
            return ResponseEntity.ok(Map.of("portalUrl", portalUrl));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Billing portal creation failed for {}: {}", principal.getUsername(), e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to open billing portal"));
        }
    }

    @PostMapping("/auto-renew/disable")
    public ResponseEntity<?> disableAutoRenew(@AuthenticationPrincipal UserDetails principal) {
        try {
            User user = resolveUser(principal);
            stripeService.disableAutoRenew(user);
            return ResponseEntity.ok(Map.of(
                    "message", "Auto-renewal disabled. Your subscription will remain active until the end of the current billing period.",
                    "autoRenewEnabled", false
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Disable auto-renew failed for {}: {}", principal.getUsername(), e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to disable auto-renewal"));
        }
    }

    @PostMapping("/auto-renew/enable")
    public ResponseEntity<?> enableAutoRenew(@AuthenticationPrincipal UserDetails principal) {
        try {
            User user = resolveUser(principal);
            stripeService.enableAutoRenew(user);
            return ResponseEntity.ok(Map.of(
                    "message", "Auto-renewal re-enabled. Your subscription will renew automatically.",
                    "autoRenewEnabled", true
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Enable auto-renew failed for {}: {}", principal.getUsername(), e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to enable auto-renewal"));
        }
    }

    @PostMapping("/use-payment-method")
    public ResponseEntity<?> usePaymentMethod(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody Map<String, String> body) {
        String paymentMethodId = body.get("paymentMethodId");
        if (paymentMethodId == null || paymentMethodId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "paymentMethodId is required"));
        }
        if (!paymentMethodId.startsWith("pm_") || paymentMethodId.length() > 64) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid paymentMethodId format"));
        }
        try {
            User user = resolveUser(principal);
            stripeService.setDefaultPaymentMethod(user, paymentMethodId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (com.stripe.exception.StripeException e) {
            log.error("Stripe error on use-payment-method for {}: {} (code={})", principal.getUsername(), e.getMessage(), e.getCode());
            return ResponseEntity.badRequest().body(Map.of("error", e.getUserMessage() != null ? e.getUserMessage() : e.getMessage()));
        } catch (Exception e) {
            log.error("Use payment method failed for {}: {}", principal.getUsername(), e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to update payment method"));
        }
    }

    @GetMapping("/preview-interval-change")
    public ResponseEntity<?> previewIntervalChange(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam String interval) {
        if (interval == null || !interval.matches("^(monthly|annual|yearly)$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "interval must be monthly or annual"));
        }
        try {
            User user = resolveUser(principal);
            Map<String, Object> result = stripeService.previewIntervalChange(user, interval);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (com.stripe.exception.StripeException e) {
            log.error("Stripe preview error for {}: {}", principal.getUsername(), e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getUserMessage() != null ? e.getUserMessage() : e.getMessage()));
        } catch (Exception e) {
            log.error("Preview interval change failed for {}: {}", principal.getUsername(), e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to preview interval change"));
        }
    }

    @PostMapping("/change-interval")
    public ResponseEntity<?> changeInterval(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody Map<String, String> body) {
        String interval = body.get("interval");
        if (interval == null || !interval.matches("^(monthly|annual|yearly)$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "interval must be monthly or annual"));
        }
        try {
            User user = resolveUser(principal);
            Map<String, Object> result = stripeService.changeSubscriptionInterval(user, interval);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (com.stripe.exception.StripeException e) {
            log.error("Stripe error changing interval for {}: {} (code={})", principal.getUsername(), e.getMessage(), e.getCode());
            return ResponseEntity.badRequest().body(Map.of("error", e.getUserMessage() != null ? e.getUserMessage() : e.getMessage()));
        } catch (Exception e) {
            log.error("Change interval failed for {}: {}", principal.getUsername(), e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to switch billing interval"));
        }
    }

    @PostMapping("/cancel")
    public ResponseEntity<?> cancelSubscription(@AuthenticationPrincipal UserDetails principal) {
        try {
            User user = resolveUser(principal);
            stripeService.cancelSubscriptionImmediately(user);
            return ResponseEntity.ok(Map.of("message", "Subscription canceled immediately."));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Cancel subscription failed for {}: {}", principal.getUsername(), e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to cancel subscription"));
        }
    }

    @GetMapping("/workspace-owner-status/{workspaceId}")
    public ResponseEntity<?> getWorkspaceOwnerStatus(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable String workspaceId) {
        try {
            User caller = resolveUser(principal);
            if (!workspaceService.hasAccess(workspaceId, caller.getId())) {
                return ResponseEntity.status(403).body(Map.of("error", "No access to this workspace"));
            }
            var workspace = workspaceService.getWorkspace(workspaceId)
                    .orElseThrow(() -> new IllegalStateException("Workspace not found"));
            User owner = userRepository.findById(workspace.getOwnerId())
                    .orElseThrow(() -> new IllegalStateException("Workspace owner not found"));

            if (systemSettingsService.isEnterpriseBypass(owner.getEmail())) {
                return ResponseEntity.ok(Map.of(
                    "planName", "ENTERPRISE",
                    "status", "active",
                    "isExpired", false,
                    "enterpriseDomainBypass", true
                ));
            }

            String planName = owner.getSubscriptionPlanName() != null ? owner.getSubscriptionPlanName() : "FREE";
            String status   = owner.getSubscriptionStatus()   != null ? owner.getSubscriptionStatus()   : "";
            boolean subscriptionAccessOk = status.equalsIgnoreCase("active")
                    || status.equalsIgnoreCase("trialing")
                    || status.equalsIgnoreCase("past_due");
            boolean isExpired = !planName.equalsIgnoreCase("FREE")
                    && !status.isEmpty()
                    && !subscriptionAccessOk;

            return ResponseEntity.ok(Map.of(
                    "planName",  planName,
                    "status",    status,
                    "isExpired", isExpired
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to get workspace owner status for {}: {}", workspaceId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to check workspace status"));
        }
    }

    private User resolveUser(UserDetails principal) {
        return userRepository.findByEmail(principal.getUsername())
                .or(() -> userRepository.findByUsername(principal.getUsername()))
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found in database"));
    }

    private String orEmpty(String value) {
        return value != null ? value : "";
    }
}
