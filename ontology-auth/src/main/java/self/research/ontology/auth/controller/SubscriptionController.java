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
import self.research.ontology.auth.service.StripeService;
import self.research.ontology.auth.service.WorkspaceService;

import java.time.LocalDateTime;
import java.util.Map;

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

    public SubscriptionController(StripeService stripeService, UserRepository userRepository, WorkspaceService workspaceService) {
        this.stripeService = stripeService;
        this.userRepository = userRepository;
        this.workspaceService = workspaceService;
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
        return ResponseEntity.ok(Map.of(
                "planName",              orEmpty(user.getSubscriptionPlanName()),
                "status",                orEmpty(user.getSubscriptionStatus()),
                "billingInterval",       orEmpty(user.getBillingInterval()),
                "autoRenewEnabled",      user.isAutoRenewEnabled(),
                "currentPeriodEnd",      user.getSubscriptionCurrentPeriodEnd() != null
                                             ? user.getSubscriptionCurrentPeriodEnd().toString() : "",
                "canceledAt",            user.getSubscriptionCanceledAt() != null
                                             ? user.getSubscriptionCanceledAt().toString() : "",
                "hasStripeCustomer",     user.getStripeCustomerId() != null
        ));
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
        } catch (IllegalStateException e) {
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
                    "stripePublishableKey", stripeService.getPublishableKey()
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
                return ResponseEntity.ok(Map.of("message", "Account subscription canceled successfully."));
            }
            if (!workspaceService.hasAccess(workspaceId, user.getId())) {
                return ResponseEntity.status(403).body(Map.of("error", "You don't have access to this workspace"));
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
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private User resolveUser(UserDetails principal) {
        return userRepository.findByUsername(principal.getUsername())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found in database"));
    }

    private String orEmpty(String value) {
        return value != null ? value : "";
    }
}
