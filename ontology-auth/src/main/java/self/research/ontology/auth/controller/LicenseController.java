package self.research.ontology.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.service.LicenseSigningService;
import self.research.ontology.auth.service.SystemSettingsService;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Issues a downloadable, signed desktop-license file for the authenticated
 * user's current plan. The user imports this file into OntoCode Desktop, which
 * verifies the signature and derives the whole user identity from it.
 */
@RestController
@RequestMapping("/api/billing/license")
public class LicenseController {

    private static final Logger log = LoggerFactory.getLogger(LicenseController.class);

    private final UserRepository userRepository;
    private final LicenseSigningService licenseSigningService;
    private final SystemSettingsService systemSettingsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LicenseController(UserRepository userRepository,
                             LicenseSigningService licenseSigningService,
                             SystemSettingsService systemSettingsService) {
        this.userRepository = userRepository;
        this.licenseSigningService = licenseSigningService;
        this.systemSettingsService = systemSettingsService;
    }

    @GetMapping("/download")
    public ResponseEntity<?> download(@AuthenticationPrincipal UserDetails principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        if (!licenseSigningService.isConfigured()) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "License signing is not configured on this server"));
        }

        User user = userRepository.findByEmail(principal.getUsername())
                .or(() -> userRepository.findByUsername(principal.getUsername()))
                .orElse(null);
        if (user == null) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        }

        String plan = resolvePlan(user);
        LocalDateTime expiresAt = "FREE".equals(plan) ? null : user.getSubscriptionCurrentPeriodEnd();

        try {
            Map<String, Object> license = licenseSigningService.issue(
                    user.getUsername(), user.getEmail(), plan, expiresAt, featuresFor(plan));
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(license);
            String fileName = "ontocode-" + plan.toLowerCase() + ".lic";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(bytes);
        } catch (Exception e) {
            log.error("Failed to issue license for {}", user.getEmail(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to issue license"));
        }
    }

    private String resolvePlan(User user) {
        if (systemSettingsService.isEnterpriseDomain(user.getEmail())) {
            return "ENTERPRISE";
        }
        String plan = user.getSubscriptionPlanName();
        return plan != null && !plan.isBlank() ? plan.toUpperCase() : "FREE";
    }

    /** Advisory feature flags carried in the license (plan drives client gating). */
    private Map<String, Object> featuresFor(String plan) {
        boolean paid = "PRO".equals(plan) || "ENTERPRISE".equals(plan);
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("hasExport", true);
        features.put("hasReasonerAccess", true);
        features.put("hasPlugins", true);
        features.put("hasVersionHistory", paid);
        features.put("hasMergeFeature", paid);
        features.put("hasCollaboration", "ENTERPRISE".equals(plan));
        return features;
    }
}
