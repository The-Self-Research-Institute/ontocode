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

@RestController
@ConditionalOnProperty(name = "ontocode.desktop.mode", havingValue = "true")
public class DesktopController {

    private static final Logger log = LoggerFactory.getLogger(DesktopController.class);

    private static final String DESKTOP_USER_ID      = "desktop-user-local";
    private static final String DESKTOP_WORKSPACE_ID = "desktop-workspace-local";
    private static final String DESKTOP_PROJECT_ID   = "desktop-project-local";
    private static final String DESKTOP_EMAIL        = "local@desktop";
    private static final String DESKTOP_USERNAME     = "Desktop User";
    private static final String DESKTOP_PLAN         = "PRO";

    private static final String DESKTOP_TOKEN = buildDesktopJwt();

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

    @GetMapping("/api/auth/refresh")
    @PostMapping("/api/auth/refresh")
    public ResponseEntity<Map<String, Object>> refresh() {
        Map<String, Object> resp = loginResponse();

        resp.put("jwt", DESKTOP_TOKEN);
        return ResponseEntity.ok(resp);
    }

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

    @GetMapping("/api/invitations")
    public ResponseEntity<List<Object>> invitations() {
        return ResponseEntity.ok(Collections.emptyList());
    }

    @GetMapping("/api/admin/users")
    public ResponseEntity<List<Map<String, Object>>> adminUsers() {
        return ResponseEntity.ok(List.of(userProfile()));
    }

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

    private static String buildDesktopJwt() {

        String header = base64url("{\"alg\":\"none\",\"typ\":\"JWT\"}");

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
