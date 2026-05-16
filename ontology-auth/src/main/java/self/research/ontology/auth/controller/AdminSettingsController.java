package self.research.ontology.auth.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.auth.model.SystemSettings;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;
import self.research.ontology.auth.service.SystemSettingsService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Admin-only endpoints for runtime system settings.
 * All routes require ROLE_ADMIN.
 */
@RestController
@RequestMapping("/api/admin/settings")
public class AdminSettingsController {

    private static final Logger log = LoggerFactory.getLogger(AdminSettingsController.class);

    private final SystemSettingsService settingsService;
    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;

    public AdminSettingsController(SystemSettingsService settingsService,
                                   UserRepository userRepository,
                                   WorkspaceRepository workspaceRepository) {
        this.settingsService = settingsService;
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
    }

    /** GET /api/admin/settings — return current settings */
    @GetMapping
    public ResponseEntity<?> getSettings() {
        if (!isAdmin()) return forbidden();
        return ResponseEntity.ok(settingsService.get());
    }

    /** PUT /api/admin/settings — replace full settings object */
    @PutMapping
    public ResponseEntity<?> updateSettings(@RequestBody SystemSettings body) {
        if (!isAdmin()) return forbidden();
        SystemSettings saved = settingsService.save(body, currentEmail());
        return ResponseEntity.ok(saved);
    }

    /** PATCH /api/admin/settings/maintenance — toggle maintenance on/off */
    @PatchMapping("/maintenance")
    public ResponseEntity<?> setMaintenance(@RequestBody Map<String, Object> body) {
        if (!isAdmin()) return forbidden();
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        SystemSettings s = settingsService.get();
        s.setMaintenanceModeEnabled(enabled);

        @SuppressWarnings("unchecked")
        List<String> domains = (List<String>) body.get("allowedDomains");
        if (domains != null) s.setMaintenanceAllowedDomains(domains);

        SystemSettings saved = settingsService.save(s, currentEmail());
        log.info("Maintenance mode {} by {}", enabled ? "ENABLED" : "DISABLED", currentEmail());
        return ResponseEntity.ok(Map.of(
            "maintenanceModeEnabled", saved.isMaintenanceModeEnabled(),
            "maintenanceAllowedDomains", saved.getMaintenanceAllowedDomains()
        ));
    }

    /** PATCH /api/admin/settings/enterprise-domains — update enterprise domain bypass list */
    @PatchMapping("/enterprise-domains")
    public ResponseEntity<?> setEnterpriseDomains(@RequestBody Map<String, Object> body) {
        if (!isAdmin()) return forbidden();

        @SuppressWarnings("unchecked")
        List<String> domains = (List<String>) body.get("domains");
        if (domains == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "domains list is required"));
        }

        SystemSettings s = settingsService.get();
        s.setEnterpriseDomains(domains);
        SystemSettings saved = settingsService.save(s, currentEmail());

        // Apply enterprise plan to existing workspaces whose owner matches the new domain list
        applyEnterpriseToExistingWorkspaces(saved.getEnterpriseDomains());

        return ResponseEntity.ok(Map.of(
            "enterpriseDomains", saved.getEnterpriseDomains()
        ));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void applyEnterpriseToExistingWorkspaces(List<String> enterpriseDomains) {
        if (enterpriseDomains == null || enterpriseDomains.isEmpty()) return;
        for (String domain : enterpriseDomains) {
            String domainLower = domain.trim().toLowerCase();
            // Find users whose email ends with this domain
            userRepository.findAll().stream()
                .filter(u -> u.getEmail() != null && u.getEmail().toLowerCase().endsWith("@" + domainLower))
                .forEach(user -> {
                    workspaceRepository.findByOwnerId(user.getId()).stream()
                        .filter(ws -> !Boolean.TRUE.equals(ws.getIsDeleted()))
                        .forEach(ws -> {
                            if (!"ENTERPRISE".equalsIgnoreCase(ws.getSubscriptionPlan())) {
                                ws.setSubscriptionPlan("ENTERPRISE");
                                ws.setCollaborationEnabled(true);
                                // No Stripe subscription — domain bypass
                                workspaceRepository.save(ws);
                                log.info("Auto-granted ENTERPRISE to workspace {} (owner={})", ws.getWorkspaceId(), user.getEmail());
                            }
                        });
                });
        }
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private String currentEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "unknown";
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
    }
}
