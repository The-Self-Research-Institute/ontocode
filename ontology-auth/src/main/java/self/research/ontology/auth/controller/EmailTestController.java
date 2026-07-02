package self.research.ontology.auth.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.service.EmailService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Admin-only test endpoints for manually triggering every transactional /
 * billing email. Useful to verify SMTP configuration and to preview a
 * template after a copy / styling change.
 *
 * <p>Disabled by default. Enable per environment with
 * {@code email.test-endpoints.enabled=true}. Recommended: turn on in dev
 * and staging, leave OFF in production.
 *
 * <p>All endpoints require the caller to hold {@code ROLE_ADMIN}.
 *
 * <p>Endpoints accept a JSON body of the form:
 * <pre>{ "to": "you@example.com", "username": "Pranesh" }</pre>
 * Other fields default to representative sample values so an operator can
 * "smoke test" the whole pipeline with one curl.
 */
@RestController
@RequestMapping("/api/admin/test-emails")
@ConditionalOnProperty(prefix = "email.test-endpoints", name = "enabled", havingValue = "true")
@CrossOrigin(originPatterns = "*")
public class EmailTestController {

    private static final Logger log = LoggerFactory.getLogger(EmailTestController.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMMM d, yyyy");

    @Autowired
    private EmailService emailService;

    @Autowired
    private UserRepository userRepository;

    @Value("${app.base-url:http://localhost:8082}")
    private String baseUrl;

    // ─────────────────────────────────────────────────────────────────────
    // Request body shape
    // ─────────────────────────────────────────────────────────────────────

    public record TestEmailRequest(
        String to,
        String username,
        String planName,
        String interval,
        Long daysBefore,
        String amount,
        Long days
    ) {}

    // ─────────────────────────────────────────────────────────────────────
    // Auth helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Resolve the authenticated user and verify ROLE_ADMIN. Returns the
     * user on success, throws on missing principal so the @ExceptionHandler
     * can convert to a 401.
     */
    private User requireAdmin(UserDetails principal) {
        if (principal == null) {
            throw new SecurityException("Authentication required.");
        }
        Optional<User> userOpt = userRepository.findByUsername(principal.getUsername());
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByEmail(principal.getUsername());
        }
        User user = userOpt.orElseThrow(() -> new SecurityException("User not found."));
        if (user.getRoles() == null || !user.getRoles().contains("ROLE_ADMIN")) {
            throw new SecurityException("Admin role required.");
        }
        return user;
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleSecurity(SecurityException e) {
        return ResponseEntity.status(403).body(Map.of(
                "success", false,
                "error", e.getMessage()));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Index — list every endpoint and what it exercises
    // ─────────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<?> index(@AuthenticationPrincipal UserDetails principal) {
        requireAdmin(principal);
        return ResponseEntity.ok(Map.of(
                "endpoints", List.of(
                        Map.of("path", "POST /verification",       "purpose", "sendVerificationEmail"),
                        Map.of("path", "POST /password-reset",     "purpose", "sendPasswordResetEmail"),
                        Map.of("path", "POST /password-changed",   "purpose", "sendPasswordChangeEmail"),
                        Map.of("path", "POST /trial-started",      "purpose", "sendTrialStartedEmail"),
                        Map.of("path", "POST /trial-ending",       "purpose", "sendTrialEndingReminderEmail"),
                        Map.of("path", "POST /payment-succeeded",  "purpose", "sendPaymentSucceededEmail"),
                        Map.of("path", "POST /payment-failed",     "purpose", "sendPaymentFailedEmail"),
                        Map.of("path", "POST /subscription-cancelled", "purpose", "sendSubscriptionCancelledEmail"),
                        Map.of("path", "POST /renewal-reminder",   "purpose", "sendRenewalReminderEmail"),
                        Map.of("path", "POST /project-access",     "purpose", "sendProjectAccessEmail")
                ),
                "bodySchema", Map.of(
                        "to (required)",  "Recipient email address",
                        "username",       "Display name; defaults to local-part of `to`",
                        "planName",       "PRO or ENTERPRISE; default PRO",
                        "interval",       "monthly / yearly; default monthly",
                        "daysBefore",     "Days-before-event for reminder emails; default 7",
                        "amount",         "Pretty amount string; default $59.00 USD",
                        "days",           "Trial days for trial-started; default 14"
                ),
                "note", "Body fields beyond `to` all have defaults, so the simplest call is just { \"to\": \"you@example.com\" }."
        ));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Auth / account emails
    // ─────────────────────────────────────────────────────────────────────

    @PostMapping("/verification")
    public ResponseEntity<?> verification(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody TestEmailRequest req) {
        requireAdmin(principal);
        String to = requireTo(req);
        // Token is a synthetic placeholder so a tester can see the full URL
        // pattern without burning a real verification token.
        emailService.sendVerificationEmail(to, "test-token-" + System.currentTimeMillis());
        return ok("verification", to);
    }

    @PostMapping("/password-reset")
    public ResponseEntity<?> passwordReset(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody TestEmailRequest req) {
        requireAdmin(principal);
        String to = requireTo(req);
        emailService.sendPasswordResetEmail(to, "test-reset-" + System.currentTimeMillis());
        return ok("password-reset", to);
    }

    @PostMapping("/password-changed")
    public ResponseEntity<?> passwordChanged(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody TestEmailRequest req) {
        requireAdmin(principal);
        String to = requireTo(req);
        emailService.sendPasswordChangeEmail(to, defaultUsername(req, to));
        return ok("password-changed", to);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Billing emails
    // ─────────────────────────────────────────────────────────────────────

    @PostMapping("/trial-started")
    public ResponseEntity<?> trialStarted(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody TestEmailRequest req) {
        requireAdmin(principal);
        String to = requireTo(req);
        long days = req.days() != null && req.days() > 0 ? req.days() : 14L;
        String trialEnd = LocalDateTime.now().plusDays(days).format(DATE_FMT);
        emailService.sendTrialStartedEmail(
                to, defaultUsername(req, to), defaultPlan(req), trialEnd, baseUrl + "/billing");
        return ok("trial-started", to);
    }

    @PostMapping("/trial-ending")
    public ResponseEntity<?> trialEnding(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody TestEmailRequest req) {
        requireAdmin(principal);
        String to = requireTo(req);
        long daysBefore = req.daysBefore() != null && req.daysBefore() > 0 ? req.daysBefore() : 7L;
        String trialEnd = LocalDateTime.now().plusDays(daysBefore).format(DATE_FMT);
        emailService.sendTrialEndingReminderEmail(
                to, defaultUsername(req, to), defaultPlan(req), daysBefore, trialEnd, baseUrl + "/billing");
        return ok("trial-ending", to);
    }

    @PostMapping("/payment-succeeded")
    public ResponseEntity<?> paymentSucceeded(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody TestEmailRequest req) {
        requireAdmin(principal);
        String to = requireTo(req);
        String amount = req.amount() != null && !req.amount().isBlank() ? req.amount() : "$59.00 USD";
        String nextBilling = LocalDateTime.now().plusMonths(1).format(DATE_FMT);
        // Synthetic invoice URL so the "View invoice" button renders.
        String invoiceUrl = baseUrl + "/invoices/test-" + System.currentTimeMillis();
        emailService.sendPaymentSucceededEmail(
                to, defaultUsername(req, to), defaultPlan(req), amount, nextBilling, invoiceUrl);
        return ok("payment-succeeded", to);
    }

    @PostMapping("/payment-failed")
    public ResponseEntity<?> paymentFailed(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody TestEmailRequest req) {
        requireAdmin(principal);
        String to = requireTo(req);
        String amount = req.amount() != null && !req.amount().isBlank() ? req.amount() : "$59.00 USD";
        emailService.sendPaymentFailedEmail(
                to, defaultUsername(req, to), defaultPlan(req), amount, baseUrl + "/billing");
        return ok("payment-failed", to);
    }

    @PostMapping("/subscription-cancelled")
    public ResponseEntity<?> subscriptionCancelled(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody TestEmailRequest req) {
        requireAdmin(principal);
        String to = requireTo(req);
        String cancelled = LocalDateTime.now().format(DATE_FMT);
        emailService.sendSubscriptionCancelledEmail(
                to, defaultUsername(req, to), defaultPlan(req), cancelled);
        return ok("subscription-cancelled", to);
    }

    @PostMapping("/renewal-reminder")
    public ResponseEntity<?> renewalReminder(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody TestEmailRequest req) {
        requireAdmin(principal);
        String to = requireTo(req);
        int daysBefore = req.daysBefore() != null && req.daysBefore() > 0 ? req.daysBefore().intValue() : 7;
        String renewalDate = LocalDateTime.now().plusDays(daysBefore).format(DATE_FMT);
        String amount = req.amount() != null && !req.amount().isBlank()
                ? req.amount()
                : resolveAmount(defaultPlan(req), req.interval());
        emailService.sendRenewalReminderEmail(
                to, defaultUsername(req, to), defaultPlan(req),
                daysBefore, renewalDate, amount, baseUrl + "/billing");
        return ok("renewal-reminder", to);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Workspace / project emails
    // ─────────────────────────────────────────────────────────────────────

    @PostMapping("/project-access")
    public ResponseEntity<?> projectAccess(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody TestEmailRequest req) {
        requireAdmin(principal);
        String to = requireTo(req);
        emailService.sendProjectAccessEmail(
                to, defaultUsername(req, to),
                "Sample Project (test)", "MEMBER", "Admin Tester");
        return ok("project-access", to);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private static String requireTo(TestEmailRequest req) {
        if (req == null || req.to() == null || req.to().isBlank()) {
            throw new IllegalArgumentException("`to` is required.");
        }
        String to = req.to().trim();
        // Cheap shape check; the SMTP server is the real validator.
        if (!to.contains("@") || to.length() < 5) {
            throw new IllegalArgumentException("`to` does not look like an email address.");
        }
        return to;
    }

    private static String defaultUsername(TestEmailRequest req, String to) {
        if (req != null && req.username() != null && !req.username().isBlank()) {
            return req.username().trim();
        }
        // Fall back to the local-part of the email so the email body still
        // looks personalised even when the caller skipped username.
        int at = to.indexOf('@');
        return at > 0 ? to.substring(0, at) : "Tester";
    }

    private static String defaultPlan(TestEmailRequest req) {
        if (req == null || req.planName() == null || req.planName().isBlank()) {
            return "PRO";
        }
        String p = req.planName().trim().toUpperCase();
        return p.equals("ENTERPRISE") ? "ENTERPRISE" : "PRO";
    }

    private static String resolveAmount(String planName, String interval) {
        boolean yearly = "yearly".equalsIgnoreCase(interval) || "annual".equalsIgnoreCase(interval);
        return switch (planName) {
            case "ENTERPRISE" -> yearly ? "$3,588/year" : "$299/month";
            default -> yearly ? "$708/year" : "$59/month";
        };
    }

    private ResponseEntity<Map<String, Object>> ok(String type, String to) {
        log.info("[EmailTest] Triggered '{}' email to {}", type, to);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("emailType", type);
        body.put("to", to);
        body.put("note", "Check the auth-service log for 'Sent ...' / 'Failed to send ...' "
                + "and the SMTP provider dashboard / inbox for delivery.");
        return ResponseEntity.ok(body);
    }
}
