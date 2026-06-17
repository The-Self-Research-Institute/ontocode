package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.service.DraftPublishAnalysis;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;

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
    
    public DraftController(DraftTrackingService draftTrackingService) {
        this.draftTrackingService = draftTrackingService;
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
    public ResponseEntity<Map<String, Object>> getDraftStats(@PathVariable String projectId) {
        try {
            Map<String, Object> stats = draftTrackingService.getDraftStatistics(projectId);
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
            @RequestParam(required = false, defaultValue = "false") boolean merge) {
        try {
            log.info("[DRAFT API] Applying drafts for project {} user {} (force={}, merge={})",
                    projectId, userId, force, merge);

            DraftTrackingService.ApplyDraftsResult result =
                draftTrackingService.applyDrafts(projectId, userId, force, merge);

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
    
    // Request DTOs
    
    public record DraftRequest(
        List<MutationOp> ops,
        String userId,
        String username,
        String sessionId
    ) {}
}
