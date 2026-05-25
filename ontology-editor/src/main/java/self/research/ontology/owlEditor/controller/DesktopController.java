package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Desktop-mode stub endpoints that replace the Auth Service.
 *
 * Active only when ontocode.desktop.mode=true (the desktop Spring profile).
 * These endpoints live on the same port as the OWL Editor so no separate
 * Auth Service process is needed.
 *
 * The React app's auth flow calls:
 *   POST /api/auth/login           → returns a JWT + user profile
 *   POST /api/workspaces/{id}/select → returns a workspace-scoped JWT
 *   GET  /api/workspaces           → returns the single local workspace
 *   GET  /api/workspaces/{id}      → returns workspace details
 *   GET  /api/billing/subscription → returns "PRO" subscription (no limits)
 *   GET  /api/auth/validate        → validates / refreshes the JWT
 *   POST /api/auth/logout          → no-op
 *   GET  /api/auth/me              → returns current user profile
 */
@RestController
@ConditionalOnProperty(name = "ontocode.desktop.mode", havingValue = "true")
public class DesktopController {

    private static final Logger log = LoggerFactory.getLogger(DesktopController.class);

    // ── Stable desktop identifiers ────────────────────────────────────────────
    private static final String DESKTOP_USER_ID      = "desktop-user-local";
    private static final String DESKTOP_WORKSPACE_ID = "desktop-workspace-local";
    private static final String DESKTOP_PROJECT_ID   = "desktop-project-local";
    private static final String DESKTOP_EMAIL        = "local@desktop";
    private static final String DESKTOP_USERNAME     = "Desktop User";
    private static final String DESKTOP_PLAN         = "PRO";

    // ── JWT (unsigned — desktop only, never leaves localhost) ─────────────────
    // The interceptor does not validate signatures; it only decodes claims.
    private static final String DESKTOP_TOKEN = buildDesktopJwt();

    // ── Auth endpoints ────────────────────────────────────────────────────────

    @PostMapping("/api/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) Map<String, Object> body) {
        log.debug("[Desktop] POST /api/auth/login");
        return ResponseEntity.ok(loginResponse());
    }

    @GetMapping("/api/auth/validate")
    public ResponseEntity<Map<String, Object>> validate(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.debug("[Desktop] GET /api/auth/validate");
        return ResponseEntity.ok(loginResponse());
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<Map<String, Object>> me() {
        log.debug("[Desktop] GET /api/auth/me");
        return ResponseEntity.ok(userProfile());
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        return ResponseEntity.ok(Map.of("message", "logged out"));
    }

    @PostMapping("/api/auth/refresh")
    public ResponseEntity<Map<String, Object>> refresh() {
        return ResponseEntity.ok(loginResponse());
    }

    // ── Workspace endpoints ───────────────────────────────────────────────────

    @GetMapping("/api/workspaces")
    public ResponseEntity<List<Map<String, Object>>> listWorkspaces() {
        log.debug("[Desktop] GET /api/workspaces");
        return ResponseEntity.ok(List.of(workspacePayload()));
    }

    @GetMapping("/api/workspaces/{workspaceId}")
    public ResponseEntity<Map<String, Object>> getWorkspace(@PathVariable String workspaceId) {
        return ResponseEntity.ok(workspacePayload());
    }

    @PostMapping("/api/workspaces/{workspaceId}/select")
    public ResponseEntity<Map<String, Object>> selectWorkspace(@PathVariable String workspaceId) {
        log.debug("[Desktop] POST /api/workspaces/{}/select", workspaceId);
        // Return a workspace-scoped JWT — same token, just confirms selection
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", DESKTOP_TOKEN);
        resp.put("workspaceId", DESKTOP_WORKSPACE_ID);
        resp.put("role", "OWNER");
        resp.put("plan", DESKTOP_PLAN);
        resp.put("billingStatus", "ACTIVE");
        resp.put("user", userProfile());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/api/workspaces/{workspaceId}/members")
    public ResponseEntity<List<Object>> listMembers(@PathVariable String workspaceId) {
        return ResponseEntity.ok(List.of(Map.of(
            "userId", DESKTOP_USER_ID,
            "username", DESKTOP_USERNAME,
            "email", DESKTOP_EMAIL,
            "role", "OWNER"
        )));
    }

    // ── Billing / subscription ────────────────────────────────────────────────

    @GetMapping("/api/billing/subscription")
    public ResponseEntity<Map<String, Object>> subscription() {
        log.debug("[Desktop] GET /api/billing/subscription");
        Map<String, Object> sub = new LinkedHashMap<>();
        sub.put("planName", DESKTOP_PLAN);
        sub.put("billingStatus", "ACTIVE");
        sub.put("interval", "ANNUAL");
        sub.put("currentPeriodEnd", Instant.now().plus(3650, ChronoUnit.DAYS).toString());
        sub.put("autoRenew", false);
        sub.put("isDesktopLicense", true);
        sub.put("features", Map.of(
            "hasExport", true,
            "hasMultipleExportFormats", true,
            "hasCollaboration", false,
            "hasVersionHistory", true,
            "hasPlugins", true,
            "hasReasonerAccess", true,
            "hasMergeFeature", true,
            "hasAPIAccess", true
        ));
        return ResponseEntity.ok(sub);
    }

    @GetMapping("/api/billing/plans")
    public ResponseEntity<List<Map<String, Object>>> plans() {
        return ResponseEntity.ok(List.of(Map.of(
            "planId", "DESKTOP",
            "name", "Desktop",
            "description", "Full-featured offline desktop edition",
            "monthlyPrice", 0,
            "features", Map.of("hasReasonerAccess", true, "hasExport", true)
        )));
    }

    // ── Invitation stubs (no-op on desktop) ───────────────────────────────────

    @GetMapping("/api/invitations")
    public ResponseEntity<List<Object>> invitations() {
        return ResponseEntity.ok(Collections.emptyList());
    }

    // ── Admin (desktop always grants admin) ───────────────────────────────────

    @GetMapping("/api/admin/users")
    public ResponseEntity<List<Map<String, Object>>> adminUsers() {
        return ResponseEntity.ok(List.of(userProfile()));
    }

    // ── Helper builders ───────────────────────────────────────────────────────

    private Map<String, Object> loginResponse() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", DESKTOP_TOKEN);
        resp.put("user", userProfile());
        return resp;
    }

    private Map<String, Object> userProfile() {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", DESKTOP_USER_ID);
        user.put("userId", DESKTOP_USER_ID);
        user.put("username", DESKTOP_USERNAME);
        user.put("email", DESKTOP_EMAIL);
        user.put("roles", List.of("ROLE_USER", "ROLE_ADMIN"));
        user.put("subscriptionPlanName", DESKTOP_PLAN);
        user.put("subscriptionStatus", "ACTIVE");
        user.put("billingInterval", "ANNUAL");
        user.put("isAdmin", true);
        user.put("enterpriseDomainBypass", true);
        user.put("hasUsedFreeTrial", false);
        user.put("autoRenewEnabled", false);
        return user;
    }

    private Map<String, Object> workspacePayload() {
        Map<String, Object> ws = new LinkedHashMap<>();
        ws.put("workspaceId", DESKTOP_WORKSPACE_ID);
        ws.put("name", "Local Desktop Workspace");
        ws.put("ownerId", DESKTOP_USER_ID);
        ws.put("subscriptionPlan", DESKTOP_PLAN);
        ws.put("billingStatus", "ACTIVE");
        ws.put("maxMembers", 1);
        ws.put("role", "OWNER");
        ws.put("members", List.of(Map.of(
            "userId", DESKTOP_USER_ID,
            "username", DESKTOP_USERNAME,
            "email", DESKTOP_EMAIL,
            "role", "OWNER"
        )));
        return ws;
    }

    /**
     * Builds a minimal unsigned JWT whose payload carries the claims read by
     * MdcLoggingFilter, FreeViewOnlyInterceptor, and JwtClaimUtils.
     * No signature verification happens anywhere in the OWL Editor — the
     * payload is simply Base64-decoded.
     */
    private static String buildDesktopJwt() {
        // Header
        String header = base64url("{\"alg\":\"none\",\"typ\":\"JWT\"}");

        // Payload — all claims expected by the codebase
        long now = Instant.now().getEpochSecond();
        long exp = Instant.now().plus(3650, ChronoUnit.DAYS).getEpochSecond();
        String payload = base64url(String.format(
            "{\"sub\":\"%s\",\"email\":\"%s\",\"userId\":\"%s\"," +
            "\"workspaceId\":\"%s\",\"plan\":\"%s\",\"isAdmin\":true," +
            "\"roles\":[\"ROLE_USER\",\"ROLE_ADMIN\"]," +
            "\"iat\":%d,\"exp\":%d}",
            DESKTOP_USER_ID, DESKTOP_EMAIL, DESKTOP_USER_ID,
            DESKTOP_WORKSPACE_ID, DESKTOP_PLAN, now, exp
        ));

        return header + "." + payload + ".";
    }

    private static String base64url(String json) {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
