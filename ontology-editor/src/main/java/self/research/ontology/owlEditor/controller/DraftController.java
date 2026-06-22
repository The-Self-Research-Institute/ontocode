package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.DraftCopyStatus;
import self.research.ontology.owlEditor.model.merge.ConflictResolution;
import self.research.ontology.owlEditor.model.merge.ResolutionAction;
import self.research.ontology.owlEditor.service.DraftCopyService;
import self.research.ontology.owlEditor.service.DraftPublishAnalysis;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.ProjectMetadataService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for draft change operations.
 * Handles recording, applying, and discarding draft changes.
 */
@RestController
@RequestMapping("/api/ontology")
@CrossOrigin(originPatterns = "*")
public class DraftController {
    
    private static final Logger log = LoggerFactory.getLogger(DraftController.class);
    
    private final DraftTrackingService draftTrackingService;
    private final DraftCopyService draftCopyService;
    private final ProjectMetadataService metadataService;

    public DraftController(DraftTrackingService draftTrackingService,
                           DraftCopyService draftCopyService,
                           ProjectMetadataService metadataService) {
        this.draftTrackingService = draftTrackingService;
        this.draftCopyService = draftCopyService;
        this.metadataService = metadataService;
    }
    
    /**
     * Record draft mutations (doesn't apply to GraphDB yet)
     * POST /api/ontology/{projectId}/drafts
     */
    @PostMapping("/{projectId}/drafts")
    public ResponseEntity<Map<String, Object>> recordDrafts(
            @PathVariable String projectId,
            @RequestBody DraftRequest request) {
        try {
            log.info("[DRAFT API] Recording {} draft operations for project {}", 
                request.ops().size(), projectId);
            
            String userId = request.userId() != null ? request.userId() : "anonymous";
            String username = request.username() != null ? request.username() : "Anonymous";
            String sessionId = request.sessionId() != null ? request.sessionId() : 
                java.util.UUID.randomUUID().toString();
            
            List<DraftChange> drafts = draftTrackingService.recordDrafts(
                projectId, userId, username, request.ops(), sessionId);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Draft changes recorded",
                "draftCount", drafts.size(),
                "projectId", projectId
            ));
            
        } catch (Exception e) {
            log.error("[DRAFT API] Error recording drafts", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
    
    /**
     * Get all unapplied drafts for a project
     * GET /api/ontology/{projectId}/drafts
     */
    @GetMapping("/{projectId}/drafts")
    public ResponseEntity<Map<String, Object>> getDrafts(@PathVariable String projectId) {
        try {
            List<DraftChange> drafts = draftTrackingService.getUnappliedDrafts(projectId);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "projectId", projectId,
                "draftCount", drafts.size(),
                "drafts", drafts
            ));
            
        } catch (Exception e) {
            log.error("[DRAFT API] Error getting drafts", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
    
    /**
     * Get draft statistics
     * GET /api/ontology/{projectId}/drafts/stats
     */
    @GetMapping("/{projectId}/drafts/stats")
    public ResponseEntity<Map<String, Object>> getDraftStats(
            @PathVariable String projectId,
            @RequestParam(required = false) String userId) {
        try {
            Map<String, Object> stats = draftTrackingService.getDraftStatistics(projectId, userId);
            stats.put("success", true);
            stats.put("projectId", projectId);
            
            return ResponseEntity.ok(stats);
            
        } catch (Exception e) {
            log.error("[DRAFT API] Error getting draft stats", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
    
    /**
     * Preview publish conflicts before save.
     * GET /api/ontology/{projectId}/drafts/publish-preview?userId=...
     */
    @GetMapping("/{projectId}/drafts/publish-preview")
    public ResponseEntity<Map<String, Object>> publishPreview(
            @PathVariable String projectId,
            @RequestParam String userId,
            @RequestParam(required = false, defaultValue = "true") boolean axiomDetail) {
        try {
            DraftPublishAnalysis analysis = draftTrackingService.analyzePublish(projectId, userId, axiomDetail);
            Map<String, Object> response = new HashMap<>(analysis.toResponseMap());
            response.put("success", true);
            response.put("projectId", projectId);
            response.put("userId", userId);
            response.put("blocked", analysis.isBlocked(false));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[DRAFT API] Error analyzing publish preview", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Apply drafts to GraphDB (called during save)
     * POST /api/ontology/{projectId}/drafts/apply?userId=...&force=false
     */
    @PostMapping("/{projectId}/drafts/apply")
    public ResponseEntity<Map<String, Object>> applyDrafts(
            @PathVariable String projectId,
            @RequestParam String userId,
            @RequestParam(required = false, defaultValue = "false") boolean force,
            @RequestParam(required = false, defaultValue = "false") boolean merge,
            @RequestBody(required = false) Map<String, Map<String, String>> resolutionsBody) {
        try {
            log.info("[DRAFT API] Applying drafts for project {} user {} (force={}, merge={})",
                    projectId, userId, force, merge);

            Map<String, ConflictResolution> resolutions = null;
            if (merge && resolutionsBody != null && !resolutionsBody.isEmpty()) {
                resolutions = new HashMap<>();
                for (Map.Entry<String, Map<String, String>> entry : resolutionsBody.entrySet()) {
                    String actionStr = entry.getValue() != null ? entry.getValue().get("action") : null;
                    if (actionStr != null) {
                        ConflictResolution cr = new ConflictResolution();
                        try { cr.setAction(ResolutionAction.valueOf(actionStr)); } catch (IllegalArgumentException ignored) {}
                        String suffix = entry.getValue().get("renameSuffix");
                        if (suffix != null) cr.setRenameSuffix(suffix);
                        resolutions.put(entry.getKey(), cr);
                    }
                }
            }

            DraftTrackingService.ApplyDraftsResult result =
                draftTrackingService.applyDrafts(projectId, userId, force, merge, resolutions);

            if (result.isConflictBlocked()) {
                Map<String, Object> body = new HashMap<>();
                body.put("success", false);
                body.put("message", result.getMessage());
                body.put("conflictBlocked", true);
                body.put("projectId", projectId);
                if (result.getPublishAnalysis() != null) {
                    body.putAll(result.getPublishAnalysis().toResponseMap());
                }
                return ResponseEntity.status(409).body(body);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("appliedCount", result.getAppliedCount());
            response.put("message", result.getMessage());
            response.put("projectId", projectId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[DRAFT API] Error applying drafts", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Discard unapplied drafts
     * DELETE /api/ontology/{projectId}/drafts?userId=...
     */
    @DeleteMapping("/{projectId}/drafts")
    public ResponseEntity<Map<String, Object>> discardDrafts(
            @PathVariable String projectId,
            @RequestParam(required = false) String userId) {
        try {
            log.info("[DRAFT API] Discarding drafts for project {} user {}", projectId, userId);

            DraftTrackingService.DiscardDraftsResult result =
                draftTrackingService.discardDrafts(projectId, userId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("discardedCount", result.getDiscardedCount());
            response.put("message", result.getMessage());
            response.put("projectId", projectId);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("[DRAFT API] Error discarding drafts", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
    
    /**
     * Clear applied drafts (cleanup)
     * DELETE /api/ontology/{projectId}/drafts/applied
     */
    @DeleteMapping("/{projectId}/drafts/applied")
    public ResponseEntity<Map<String, Object>> clearAppliedDrafts(@PathVariable String projectId) {
        try {
            log.info("[DRAFT API] Clearing applied drafts for project {}", projectId);
            
            draftTrackingService.clearAppliedDrafts(projectId);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Applied drafts cleared",
                "projectId", projectId
            ));
            
        } catch (Exception e) {
            log.error("[DRAFT API] Error clearing applied drafts", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
    
    /**
     * Initiate a copy-on-switch draft copy for a user.
     * POST /api/ontology/{projectId}/draft/copy
     * Body: { "userId": "..." }
     *
     * Returns 409 if an import is in progress; 200 with tripleCount + mainRevisionAtCopy otherwise.
     * The actual copy runs asynchronously — poll /draft/copy/status until READY.
     */
    @PostMapping("/{projectId}/draft/copy")
    public ResponseEntity<Map<String, Object>> initiateDraftCopy(
            @PathVariable String projectId,
            @RequestBody DraftCopyRequest request) {
        try {
            DraftCopyService.InitiateResult result = draftCopyService.initiateCopy(projectId, request.userId());
            if (!result.accepted()) {
                return ResponseEntity.status(409).body(Map.of(
                        "success", false,
                        "reason", result.reason()
                ));
            }
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "tripleCount", result.tripleCount(),
                    "mainRevisionAtCopy", result.mainRevisionAtCopy()
            ));
        } catch (Exception e) {
            log.error("[DRAFT COPY API] Error initiating copy for project {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * Poll the status of an in-progress draft copy.
     * GET /api/ontology/{projectId}/draft/copy/status?userId=...
     *
     * Returns: { "status": "COPYING" | "READY" | "FAILED" | "NOT_FOUND" }
     */
    @GetMapping("/{projectId}/draft/copy/status")
    public ResponseEntity<Map<String, Object>> getDraftCopyStatus(
            @PathVariable String projectId,
            @RequestParam String userId) {
        DraftCopyStatus status = draftCopyService.getStatus(projectId, userId);
        return ResponseEntity.ok(Map.of(
                "projectId", projectId,
                "userId", userId,
                "status", status.name()
        ));
    }

    /**
     * Read project draft settings (requireDraftForMembers).
     * GET /api/ontology/{projectId}/draft/settings?userId=...
     */
    @org.springframework.web.bind.annotation.GetMapping("/{projectId}/draft/settings")
    public ResponseEntity<Map<String, Object>> getDraftSettings(
            @PathVariable String projectId,
            @RequestParam(required = false) String userId) {
        boolean requireDraft = metadataService.isRequireDraftForMembers(projectId);
        String ownerEmail = metadataService.getOwnerEmail(projectId).orElse(null);
        boolean isOwner = userId != null && userId.equals(ownerEmail);
        return ResponseEntity.ok(Map.of(
                "projectId", projectId,
                "requireDraftForMembers", requireDraft,
                "isOwner", isOwner
        ));
    }

    /**
     * Update requireDraftForMembers — owner only (caller must validate ownership).
     * PUT /api/ontology/{projectId}/draft/settings
     * Body: { "userId": "...", "requireDraftForMembers": true }
     */
    @org.springframework.web.bind.annotation.PutMapping("/{projectId}/draft/settings")
    public ResponseEntity<Map<String, Object>> updateDraftSettings(
            @PathVariable String projectId,
            @RequestBody DraftSettingsRequest request) {
        String ownerEmail = metadataService.getOwnerEmail(projectId).orElse(null);
        if (ownerEmail == null || !ownerEmail.equals(request.userId())) {
            return ResponseEntity.status(403).body(Map.of(
                    "success", false,
                    "error", "Only the project owner can change draft settings"
            ));
        }
        metadataService.setRequireDraftForMembers(projectId, request.requireDraftForMembers());
        log.info("[DRAFT SETTINGS] requireDraftForMembers={} for project {} by {}",
                request.requireDraftForMembers(), projectId, request.userId());
        return ResponseEntity.ok(Map.of(
                "success", true,
                "requireDraftForMembers", request.requireDraftForMembers()
        ));
    }

    // Request DTOs

    public record DraftCopyRequest(String userId) {}

    public record DraftSettingsRequest(String userId, boolean requireDraftForMembers) {}

    public record DraftRequest(
        List<MutationOp> ops,
        String userId,
        String username,
        String sessionId
    ) {}
}
