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
import self.research.ontology.auth.service.WorkspaceService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;

/**
 * REST endpoints for subscription management.
 * All endpoints require a valid JWT (enforced by SecurityConfig).
 */
@RestController
@RequestMapping("/api/billing")
public class SubscriptionController {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionController.class);

    private final StripeService stripeService;
    private final UserRepository userRepository;
    private final WorkspaceService workspaceService;
    private final PlanFeatureConfigService planFeatureConfigService;

    @Value("${stripe.trial-period-days:14}")
    private Long trialPeriodDays;

    public SubscriptionController(StripeService stripeService, UserRepository userRepository,
                                  WorkspaceService workspaceService, PlanFeatureConfigService planFeatureConfigService) {
        this.stripeService = stripeService;
        this.userRepository = userRepository;
        this.workspaceService = workspaceService;
        this.planFeatureConfigService = planFeatureConfigService;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/billing/plans — plan pricing (public, no auth required)
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/billing/public-config — Stripe publishable key for frontend
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/public-config")
    public ResponseEntity<?> getPublicConfig() {
        return ResponseEntity.ok(Map.of("stripePublishableKey", stripeService.getPublishableKey()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/billing/subscription — current subscription status
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/subscription")
    public ResponseEntity<?> getSubscription(@AuthenticationPrincipal UserDetails principal) {
        User user = resolveUser(principal);
        // Sync live status + period end from Stripe to repair any stale snapshot in MongoDB
        // (e.g. period end stuck at trial-end timestamp after immediate trial→paid upgrade).
        String liveStatus = stripeService.syncStatusFromStripe(user);
        return ResponseEntity.ok(Map.of(
                "planName",              orEmpty(user.getSubscriptionPlanName()),
                "status",                liveStatus,
                "billingInterval",       orEmpty(user.getBillingInterval()),
                "autoRenewEnabled",      user.isAutoRenewEnabled(),
                "currentPeriodEnd",      user.getSubscriptionCurrentPeriodEnd() != null
                                             ? user.getSubscriptionCurrentPeriodEnd().toString() : "",
                "canceledAt",            user.getSubscriptionCanceledAt() != null
                                             ? user.getSubscriptionCanceledAt().toString() : "",
                "hasStripeCustomer",     user.getStripeCustomerId() != null,
                "hasUsedFreeTrial",      user.isHasUsedFreeTrial(),
                "trialEligible",         !user.isHasUsedFreeTrial()
                                             && user.getFirstSubscriptionAt() == null
                                             && (user.getStripeSubscriptionId() == null || user.getStripeSubscriptionId().isBlank())
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

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/billing/checkout — start a new subscription
    // Body: { "planName": "PRO", "interval": "monthly" }
    // ─────────────────────────────────────────────────────────────────────────

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

        // Accept both "annual" and "yearly" from clients; normalize for Stripe price mapping.
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

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/billing/setup — create SetupIntent to collect card details
    // Returns: { clientSecret, stripePublishableKey }
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/billing/subscribe — create subscription after card is set up
    // Body: { "setupIntentId": "seti_xxx", "planName": "PRO", "interval": "monthly", "workspaceId": "..." }
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/billing/update-payment-method — swap card after setup
    // Body: { "setupIntentId": "seti_xxx", "workspaceId": "..." }
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/billing/cancel-workspace — cancel a specific workspace subscription
    // Body: { "workspaceId": "..." }
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/cancel-workspace")
    public ResponseEntity<?> cancelWorkspaceSubscription(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody Map<String, String> body) {
        String workspaceId = body.get("workspaceId");
        try {
            User user = resolveUser(principal);
            // Empty workspaceId = account-level cancellation (Model B)
            if (workspaceId == null || workspaceId.isBlank()) {
                stripeService.cancelAccountSubscription(user);
                return ResponseEntity.ok(Map.of(
                        "message", "Auto-renewal disabled. Your subscription remains active until the end of the current billing period."));
            }
            // Bug #42: cancellation is destructive and changes the
            // billing relationship for every member of the workspace.
            // hasAccess() only proves membership — we need ownership.
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

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/billing/portal — open Stripe customer portal
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/billing/auto-renew/disable — turn off auto-renewal
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/billing/auto-renew/enable — re-enable auto-renewal
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/billing/cancel — immediately cancel subscription
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/billing/workspace-owner-status/{workspaceId}
    // Returns the workspace owner's subscription status so members/viewers
    // can be redirected out if the owner's plan has expired.
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private User resolveUser(UserDetails principal) {
        return userRepository.findByEmail(principal.getUsername())
                .or(() -> userRepository.findByUsername(principal.getUsername()))
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found in database"));
    }

    private String orEmpty(String value) {
        return value != null ? value : "";
    }
}
