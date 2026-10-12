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
import self.research.ontology.owlEditor.service.DesktopOntologyLoader;
import self.research.ontology.owlEditor.service.DesktopOpenMetricsService;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.StorageManager;

import java.util.Map;

@RestController
@ConditionalOnProperty(name = "ontocode.desktop.mode", havingValue = "true")
public class DesktopFusekiController {

    private static final Logger log = LoggerFactory.getLogger(DesktopFusekiController.class);

    private static final String DESKTOP_USER_ID = "desktop-user-local";

    private final ProjectImportService projectImportService;
    private final DesktopOpenMetricsService openMetricsService;
    @Nullable
    private final DesktopFusekiSyncScheduler fusekiSyncScheduler;
    @Nullable
    private final DesktopOntologyLoader desktopOntologyLoader;
    private final StorageManager storageManager;
    private final DraftTrackingService draftTrackingService;

    public DesktopFusekiController(ProjectImportService projectImportService,
                                   DesktopOpenMetricsService openMetricsService,
                                   @Autowired(required = false) @Nullable DesktopFusekiSyncScheduler fusekiSyncScheduler,
                                   @Autowired(required = false) @Nullable DesktopOntologyLoader desktopOntologyLoader,
                                   StorageManager storageManager,
                                   DraftTrackingService draftTrackingService) {
        this.projectImportService = projectImportService;
        this.openMetricsService = openMetricsService;
        this.fusekiSyncScheduler = fusekiSyncScheduler;
        this.desktopOntologyLoader = desktopOntologyLoader;
        this.storageManager = storageManager;
        this.draftTrackingService = draftTrackingService;
    }

    @PostMapping("/api/desktop/save/{projectId:.+}")
    public ResponseEntity<Map<String, Object>> saveProject(@PathVariable String projectId) {
        log.info("[Desktop] POST /api/desktop/save/{}", projectId);
        if (desktopOntologyLoader == null) {
            return ResponseEntity.status(503).body(Map.of("saved", false, "error", "fast-open disabled"));
        }
        try {
            boolean saved = desktopOntologyLoader.saveProject(projectId);
            if (saved) {
                // The file-based save above is the desktop source of truth; the Mongo-tracked
                // draft records exist only to drive the "pending drafts" UI, so once the save
                // lands they're stale and must be cleared or that UI shows unsaved work forever.
                try {
                    draftTrackingService.discardDrafts(projectId, DESKTOP_USER_ID);
                } catch (Exception e) {
                    log.warn("[Desktop] Failed to clear draft tracking after save for {}: {}", projectId, e.getMessage());
                }
            }
            return ResponseEntity.ok(Map.of("saved", saved, "hasDraft", storageManager.hasDraft(projectId)));
        } catch (java.io.IOException e) {
            log.error("[Desktop] Save failed for {}: {}", projectId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("saved", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/api/desktop/discard-draft/{projectId:.+}")
    public ResponseEntity<Map<String, Object>> discardDraft(@PathVariable String projectId) {
        log.info("[Desktop] POST /api/desktop/discard-draft/{}", projectId);
        if (desktopOntologyLoader == null) {
            return ResponseEntity.status(503).body(Map.of("discarded", false, "error", "fast-open disabled"));
        }
        try {
            desktopOntologyLoader.discardDraft(projectId);
            return ResponseEntity.ok(Map.of("discarded", true));
        } catch (java.io.IOException e) {
            log.error("[Desktop] Discard failed for {}: {}", projectId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("discarded", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/api/desktop/draft-status/{projectId:.+}")
    public ResponseEntity<Map<String, Object>> draftStatus(@PathVariable String projectId) {
        return ResponseEntity.ok(Map.of("hasDraft", storageManager.hasDraft(projectId)));
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
