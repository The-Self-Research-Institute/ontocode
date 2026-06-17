package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.DesktopFusekiSyncScheduler;
import self.research.ontology.owlEditor.service.DesktopOpenMetricsService;
import self.research.ontology.owlEditor.service.ProjectImportService;

import java.util.Map;

/**
 * Fuseki sync + desktop metrics endpoints.
 *
 * Lives outside {@link DesktopController} because {@code DesktopApplication}
 * excludes that class from component scan (auth stubs are served by ontology-auth).
 */
@RestController
@ConditionalOnProperty(name = "ontocode.desktop.mode", havingValue = "true")
public class DesktopFusekiController {

    private static final Logger log = LoggerFactory.getLogger(DesktopFusekiController.class);

    private final ProjectImportService projectImportService;
    private final DesktopOpenMetricsService openMetricsService;
    @Nullable
    private final DesktopFusekiSyncScheduler fusekiSyncScheduler;

    public DesktopFusekiController(ProjectImportService projectImportService,
                                   DesktopOpenMetricsService openMetricsService,
                                   @Autowired(required = false) @Nullable DesktopFusekiSyncScheduler fusekiSyncScheduler) {
        this.projectImportService = projectImportService;
        this.openMetricsService = openMetricsService;
        this.fusekiSyncScheduler = fusekiSyncScheduler;
    }

    @PostMapping("/api/desktop/sync-fuseki/{projectId:.+}")
    public ResponseEntity<Map<String, Object>> syncFuseki(@PathVariable String projectId) {
        log.info("[Desktop] POST /api/desktop/sync-fuseki/{}", projectId);
        return ResponseEntity.ok(projectImportService.syncProjectToFuseki(projectId));
    }

    @PostMapping("/api/desktop/schedule-fuseki-sync/{projectId:.+}")
    public ResponseEntity<Map<String, Object>> scheduleFusekiSync(@PathVariable String projectId) {
        log.debug("[Desktop] POST /api/desktop/schedule-fuseki-sync/{}", projectId);
        if (fusekiSyncScheduler != null) {
            fusekiSyncScheduler.scheduleAfterOpen(projectId);
            return ResponseEntity.accepted().body(Map.of("scheduled", true));
        }
        return ResponseEntity.ok(Map.of("scheduled", false, "reason", "owlapi-first disabled"));
    }

    @GetMapping("/api/desktop/fuseki-status/{projectId:.+}")
    public ResponseEntity<Map<String, Object>> fusekiStatus(@PathVariable String projectId) {
        return ResponseEntity.ok(Map.of(
            "fusekiSyncPending", projectImportService.isFusekiSyncPending(projectId),
            "owlApiFirst", true
        ));
    }

    @GetMapping("/api/desktop/open-metrics/{projectId:.+}")
    public ResponseEntity<Map<String, Object>> openMetrics(@PathVariable String projectId) {
        return ResponseEntity.ok(openMetricsService.get(projectId));
    }
}
