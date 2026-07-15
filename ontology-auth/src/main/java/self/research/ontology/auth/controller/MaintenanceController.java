package self.research.ontology.auth.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.auth.model.SystemSettings;
import self.research.ontology.auth.service.SystemSettingsService;

import java.util.Map;

/**
 * Public endpoints for maintenance status — no authentication required.
 */
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

    private final SystemSettingsService settingsService;

    public MaintenanceController(SystemSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * GET /api/maintenance/status — called by the frontend on startup to detect maintenance.
     * Returns HTTP 200 with active=false when not in maintenance, or active=true with message.
     */
    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        SystemSettings s = settingsService.get();
        boolean active = s.isMaintenanceCurrentlyActive();
        return ResponseEntity.ok(Map.of(
            "active", active,
            "message", s.getMaintenanceMessage()
        ));
    }
}
