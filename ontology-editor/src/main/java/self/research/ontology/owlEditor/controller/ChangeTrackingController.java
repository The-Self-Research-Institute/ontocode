package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.model.OntologyChange;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.ChangeTrackingService;
import self.research.ontology.owlEditor.service.GraphDBHistoryService;
import self.research.ontology.owlEditor.service.HistorySyncService;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for change tracking operations.
 */
@RestController
@RequestMapping("/api/ontology")
@CrossOrigin(originPatterns = "*")
public class ChangeTrackingController {

    private static final Logger log = LoggerFactory.getLogger(ChangeTrackingController.class);

    @Autowired
    private ChangeTrackingService changeTrackingService;
    
    @Autowired
    private GraphDBHistoryService graphDBHistoryService;
    
    @Autowired
    private HistorySyncService historySyncService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    @Autowired
    private self.research.ontology.owlEditor.service.OntologyMutationService ontologyMutationService;

    /**
     * Get change history for a project
     * GET /api/ontology/{projectId}/changes/history
     */
    @GetMapping("/{projectId}/changes/history")
    public ResponseEntity<Map<String, Object>> getHistory(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        try {
            List<OntologyChange> changes = changeTrackingService.getProjectHistory(projectId, limit);
            
            List<Map<String, Object>> changeList = changes.stream()
                .map(this::changeToMap)
                .collect(Collectors.toList());
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "projectId", projectId,
                "changeCount", changes.size(),
                "changes", changeList
            ));
            
        } catch (Exception e) {
            log.error("Error getting change history", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get changes for a specific entity
     * GET /api/ontology/{projectId}/changes/entity
     */
    @GetMapping("/{projectId}/changes/entity")
    public ResponseEntity<Map<String, Object>> getEntityHistory(
            @PathVariable String projectId,
            @RequestParam String entityIRI
    ) {
        try {
            List<OntologyChange> changes = changeTrackingService.getEntityHistory(projectId, entityIRI);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "entityIRI", entityIRI,
                "changeCount", changes.size(),
                "changes", changes.stream()
                    .map(this::changeToMap)
                    .collect(Collectors.toList())
            ));
            
        } catch (Exception e) {
            log.error("Error getting entity history", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get changes by user
     * GET /api/ontology/{projectId}/changes/user/{userId}
     */
    @GetMapping("/{projectId}/changes/user/{userId}")
    public ResponseEntity<Map<String, Object>> getUserChanges(
            @PathVariable String projectId,
            @PathVariable String userId
    ) {
        try {
            List<OntologyChange> changes = changeTrackingService.getUserChanges(projectId, userId);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "userId", userId,
                "changeCount", changes.size(),
                "changes", changes.stream()
                    .map(this::changeToMap)
                    .collect(Collectors.toList())
            ));
            
        } catch (Exception e) {
            log.error("Error getting user changes", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get changes in a time range
     * GET /api/ontology/{projectId}/changes/range
     */
    @GetMapping("/{projectId}/changes/range")
    public ResponseEntity<Map<String, Object>> getChangesInRange(
            @PathVariable String projectId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end
    ) {
        try {
            List<OntologyChange> changes = changeTrackingService.getChangesInRange(projectId, start, end);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "start", start.toString(),
                "end", end.toString(),
                "changeCount", changes.size(),
                "changes", changes.stream()
                    .map(this::changeToMap)
                    .collect(Collectors.toList())
            ));
            
        } catch (Exception e) {
            log.error("Error getting changes in range", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get recent changes
     * GET /api/ontology/{projectId}/changes/recent
     */
    @GetMapping("/{projectId}/changes/recent")
    public ResponseEntity<Map<String, Object>> getRecentChanges(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "20") int count
    ) {
        try {
            // Use GraphDB history service for real-time changes
            List<Map<String, Object>> changes = graphDBHistoryService.getHistory(projectId, count);
            
            // Trigger sync to MongoDB for any new changes
            historySyncService.syncRecentChanges(projectId, count);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "changes", changes
            ));
            
        } catch (Exception e) {
            log.error("Error getting recent changes", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
    
    /**
     * Get synced history changes from MongoDB (with collaboration features)
     * GET /api/ontology/{projectId}/changes/synced
     */
    @GetMapping("/{projectId}/changes/synced")
    public ResponseEntity<Map<String, Object>> getSyncedChanges(
            @PathVariable String projectId,
            @RequestParam(required = false) String status
    ) {
        try {
            List<self.research.ontology.owlEditor.model.HistoryChange> changes;
            
            if (status != null) {
                changes = historySyncService.getHistoryChangesByStatus(projectId, status);
            } else {
                changes = historySyncService.getHistoryChanges(projectId);
            }
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "projectId", projectId,
                "changeCount", changes.size(),
                "changes", changes
            ));
        } catch (Exception e) {
            log.error("Error getting synced changes", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Get change statistics
     * GET /api/ontology/{projectId}/changes/stats
     */
    @GetMapping("/{projectId}/changes/stats")
    public ResponseEntity<Map<String, Object>> getStatistics(@PathVariable String projectId) {
        try {
            Map<String, Object> stats = changeTrackingService.getChangeStatistics(projectId);
            stats.put("success", true);
            stats.put("projectId", projectId);
            
            return ResponseEntity.ok(stats);
            
        } catch (Exception e) {
            log.error("Error getting change statistics", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Revert a change
     * POST /api/ontology/{projectId}/changes/{changeId}/revert
     */
    @PostMapping("/{projectId}/changes/{changeId}/revert")
    public ResponseEntity<Map<String, Object>> revertChange(
            @PathVariable String projectId,
            @PathVariable String changeId,
            @RequestBody Map<String, String> request
    ) {
        try {
            String userId = request.get("userId");
            String username = request.get("username");
            
            boolean success = changeTrackingService.revertChange(changeId, userId, username);
            
            if (success) {
                // Broadcast revert notification to collaborators
                Map<String, Object> revertNotification = Map.of(
                    "type", "CHANGE_REVERTED",
                    "projectId", projectId,
                    "changeId", changeId,
                    "userId", userId,
                    "username", username,
                    "timestamp", System.currentTimeMillis(),
                    "message", "A change was reverted - please refresh"
                );
                messagingTemplate.convertAndSend("/topic/ontology/" + projectId, revertNotification);
                
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Change reverted successfully",
                    "changeId", changeId
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Failed to revert change"
                ));
            }
            
        } catch (Exception e) {
            log.error("Error reverting change", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Export change history
     * GET /api/ontology/{projectId}/changes/export
     */
    @GetMapping("/{projectId}/changes/export")
    public ResponseEntity<List<Map<String, Object>>> exportHistory(@PathVariable String projectId) {
        try {
            List<Map<String, Object>> export = changeTrackingService.exportChangeHistory(projectId);
            return ResponseEntity.ok(export);
            
        } catch (Exception e) {
            log.error("Error exporting change history", e);
            return ResponseEntity.status(500).body(Collections.emptyList());
        }
    }

    /**
     * Approve a change
     * POST /api/ontology/{projectId}/changes/{changeId}/approve
     */
    @PostMapping("/{projectId}/changes/{changeId}/approve")
    public ResponseEntity<Map<String, Object>> approveChange(
            @PathVariable String projectId,
            @PathVariable String changeId,
            @RequestBody(required = false) Map<String, String> request
    ) {
        try {
            String userId = request != null ? request.getOrDefault("userId", "system") : "system";
            String username = request != null ? request.getOrDefault("username", "System") : "System";
            
            boolean success = historySyncService.approveChange(changeId, userId, username);
            
            if (success) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Change approved",
                    "changeId", changeId
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Change not found"
                ));
            }
        } catch (Exception e) {
            log.error("Error approving change", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Reject a change
     * POST /api/ontology/{projectId}/changes/{changeId}/reject
     */
    @PostMapping("/{projectId}/changes/{changeId}/reject")
    public ResponseEntity<Map<String, Object>> rejectChange(
            @PathVariable String projectId,
            @PathVariable String changeId,
            @RequestBody(required = false) Map<String, String> request
    ) {
        try {
            String userId = request != null ? request.getOrDefault("userId", "system") : "system";
            String username = request != null ? request.getOrDefault("username", "System") : "System";
            
            boolean success = historySyncService.rejectChange(changeId, userId, username);
            
            if (success) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Change rejected",
                    "changeId", changeId
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Change not found"
                ));
            }
        } catch (Exception e) {
            log.error("Error rejecting change", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Rollback a change - reverts both the record and applies inverse mutation to GraphDB
     * POST /api/ontology/{projectId}/changes/rollback (changeId in body to avoid URL encoding issues)
     */
    @PostMapping("/{projectId}/changes/rollback")
    public ResponseEntity<Map<String, Object>> rollbackChangeWithBody(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> request
    ) {
        String changeId = (String) request.get("changeId");
        if (changeId == null) {
            changeId = "unknown";
        }
        return performRollback(projectId, changeId, request);
    }

    /**
     * Rollback a change - reverts both the record and applies inverse mutation to GraphDB
     * POST /api/ontology/{projectId}/changes/{changeId}/rollback (legacy endpoint)
     */
    @PostMapping("/{projectId}/changes/{changeId}/rollback")
    public ResponseEntity<Map<String, Object>> rollbackChange(
            @PathVariable String projectId,
            @PathVariable String changeId,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        return performRollback(projectId, changeId, request);
    }

    /**
     * Common rollback logic
     */
    private ResponseEntity<Map<String, Object>> performRollback(
            String projectId,
            String changeId,
            Map<String, Object> request
    ) {
        try {
            log.info("[ROLLBACK] Starting rollback for change {} in project {}", changeId, projectId);
            
            // Try to mark the change as reverted in MongoDB (may fail if change is from GraphDB only)
            boolean mongoSuccess = changeTrackingService.revertChange(changeId, "system", "System");
            
            if (!mongoSuccess) {
                log.info("[ROLLBACK] Change not found in MongoDB, will proceed with GraphDB rollback only");
            }
            
            // If request body contains mutation details, apply inverse mutation to GraphDB
            if (request != null && request.containsKey("action") && request.containsKey("entityIRI")) {
                String action = (String) request.get("action");
                String entityIRI = (String) request.get("entityIRI");
                String entityLabel = (String) request.get("entityLabel");
                String oldValue = (String) request.get("oldValue");
                String newValue = (String) request.get("newValue");
                String changeType = (String) request.get("changeType");
                
                log.info("[ROLLBACK] Request details - action: {}, entityIRI: {}, changeType: {}, entityLabel: {}", 
                    action, entityIRI, changeType, entityLabel);
                log.info("[ROLLBACK] oldValue: {}, newValue: {}", oldValue, newValue);
                
                // Validate entityIRI is not null, empty, or literal "null"
                if (entityIRI == null || entityIRI.trim().isEmpty() || "null".equalsIgnoreCase(entityIRI.trim())) {
                    log.error("[ROLLBACK] entityIRI is null, empty, or 'null' string: '{}'", entityIRI);
                    return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Entity IRI is required for rollback (received: " + entityIRI + ")"
                    ));
                }
                
                try {
                    // Create inverse mutation
                    List<self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp> inverseMutations = 
                        createInverseMutation(action, changeType, entityIRI, entityLabel, oldValue, newValue);
                    
                    boolean mutationApplied = false;
                    if (!inverseMutations.isEmpty()) {
                        log.info("[ROLLBACK] Applying {} inverse mutations to GraphDB", inverseMutations.size());
                        ontologyMutationService.apply(projectId, inverseMutations);
                        mutationApplied = true;
                    } else {
                        log.info("[ROLLBACK] No inverse mutations created (unsupported change type), will only record in history");
                    }
                    
                    // Always record the rollback in GraphDB history
                    graphDBHistoryService.recordEdit(
                        projectId,
                        "system",
                        "System",
                        "ROLLBACK_" + action.toUpperCase(),
                        entityIRI,
                        entityLabel,
                        newValue, // What we're rolling back FROM
                        oldValue, // What we're rolling back TO
                        "Rolled back: " + changeType + " on " + entityLabel
                    );
                    
                    log.info("[ROLLBACK] Successfully rolled back change {}", changeId);
                    return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", mutationApplied ? "Change rolled back successfully" : "Rollback recorded (no mutation applied for this change type)",
                        "changeId", changeId,
                        "mutationApplied", mutationApplied,
                        "mongoUpdated", mongoSuccess
                    ));
                } catch (Exception e) {
                    log.error("[ROLLBACK] Failed to apply inverse mutation", e);
                    return ResponseEntity.status(500).body(Map.of(
                        "success", false,
                        "error", "Failed to apply inverse mutation: " + e.getMessage(),
                        "changeId", changeId
                    ));
                }
            } else {
                // No mutation details provided - only MongoDB was updated
                if (mongoSuccess) {
                    return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Change marked as reverted in MongoDB (no GraphDB mutation applied)",
                        "changeId", changeId,
                        "mutationApplied", false
                    ));
                } else {
                    return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Change not found and no mutation details provided for rollback"
                    ));
                }
            }
            
        } catch (Exception e) {
            log.error("[ROLLBACK] Error rolling back change", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
    
    /**
     * Create inverse mutation operations for rollback
     */
    private List<self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp> createInverseMutation(
            String action, String changeType, String entityIRI, String entityLabel, String oldValue, String newValue) {
        
        List<self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp> mutations = new ArrayList<>();
        
        log.info("[ROLLBACK] Creating inverse mutation - action: {}, changeType: {}, entityIRI: {}", action, changeType, entityIRI);
        log.info("[ROLLBACK] oldValue: {}, newValue: {}, entityLabel: {}", oldValue, newValue, entityLabel);
        
        String actionLower = action != null ? action.toLowerCase() : "";
        String typeLower = changeType != null ? changeType.toLowerCase() : "";
        
        // Determine the inverse operation based on action type
        switch (actionLower) {
            case "added":
                // If something was added, we need to delete it
                if (typeLower.contains("class")) {
                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "deleteClass", entityIRI, null, null, null, null, null, null
                    ));
                } else if (typeLower.contains("property")) {
                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "deleteProperty", entityIRI, null, null, null, null, null, null
                    ));
                } else if (typeLower.contains("individual")) {
                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "deleteIndividual", entityIRI, null, null, null, null, null, null
                    ));
                } else if (typeLower.contains("annotation")) {
                    // Annotation rollback requires property info - skip mutation but record in history
                    log.warn("[ROLLBACK] Annotation rollback requires property info - skipping mutation");
                } else {
                    log.warn("[ROLLBACK] Unknown type for 'added' action: {}, skipping mutation", changeType);
                }
                break;
                
            case "deleted":
                // If something was deleted, we need to add it back
                if (typeLower.contains("class")) {
                    // For deleted class, we recreate it with label - use owl:Thing as parent
                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "createClass", entityIRI, entityLabel != null ? entityLabel : extractLabel(entityIRI), 
                        "http://www.w3.org/2002/07/owl#Thing", null, null, null, null
                    ));
                } else if (typeLower.contains("property")) {
                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "createProperty", entityIRI, entityLabel, null, null, null, null, null
                    ));
                } else if (typeLower.contains("individual")) {
                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "createIndividual", entityIRI, entityLabel, null, null, null, null, null
                    ));
                } else if (typeLower.contains("annotation")) {
                    // Annotation rollback requires property info - skip mutation but record in history
                    log.warn("[ROLLBACK] Annotation rollback requires property info - skipping mutation");
                } else {
                    log.warn("[ROLLBACK] Unknown type for 'deleted' action: {}, skipping mutation", changeType);
                }
                break;
                
            case "modified":
                // If something was modified, we need to change it back
                if (typeLower.contains("label")) {
                    // For label change, restore old label
                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "updateClassLabel", entityIRI, oldValue != null ? oldValue : entityLabel, null, null, null, null, null
                    ));
                } else if (typeLower.contains("annotation")) {
                    // Annotation rollback requires property info - skip mutation but record in history
                    log.warn("[ROLLBACK] Annotation rollback requires property info - skipping mutation");
                } else {
                    log.warn("[ROLLBACK] Unknown type for 'modified' action: {}, skipping mutation", changeType);
                }
                break;
                
            default:
                log.warn("[ROLLBACK] Unknown action: {}, skipping mutation", action);
        }
        
        log.info("[ROLLBACK] Created {} inverse mutations", mutations.size());
        return mutations;
    }
    
    /**
     * Extract label from IRI
     */
    private String extractLabel(String iri) {
        if (iri == null) return "Unknown";
        String[] parts = iri.split("[#/]");
        return parts.length > 0 ? parts[parts.length - 1] : "Unknown";
    }

    /**
     * Add comment to a change
     * POST /api/ontology/{projectId}/changes/{changeId}/comments
     */
    @PostMapping("/{projectId}/changes/{changeId}/comments")
    public ResponseEntity<Map<String, Object>> addComment(
            @PathVariable String projectId,
            @PathVariable String changeId,
            @RequestBody Map<String, String> request
    ) {
        try {
            String text = request.get("text");
            String userId = request.getOrDefault("userId", "system");
            String username = request.getOrDefault("username", "System");
            
            boolean success = historySyncService.addComment(changeId, userId, username, text);
            
            if (success) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Comment added",
                    "changeId", changeId,
                    "comment", text
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Change not found"
                ));
            }
        } catch (Exception e) {
            log.error("Error adding comment", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * Resolve conflict
     * POST /api/ontology/{projectId}/changes/{changeId}/resolve-conflict
     */
    @PostMapping("/{projectId}/changes/{changeId}/resolve-conflict")
    public ResponseEntity<Map<String, Object>> resolveConflict(
            @PathVariable String projectId,
            @PathVariable String changeId,
            @RequestBody Map<String, String> request
    ) {
        try {
            String resolution = request.get("resolution");
            String userId = request.getOrDefault("userId", "system");
            String username = request.getOrDefault("username", "System");
            
            boolean success = historySyncService.resolveConflict(changeId, userId, username, resolution);
            
            if (success) {
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Conflict resolved",
                    "changeId", changeId,
                    "resolution", resolution
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Change not found"
                ));
            }
        } catch (Exception e) {
            log.error("Error resolving conflict", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    // Helper method to convert OntologyChange to Map
    private Map<String, Object> changeToMap(OntologyChange change) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", change.getId());
        map.put("timestamp", change.getTimestamp().toString());
        map.put("username", change.getUsername());
        map.put("userId", change.getUserId());
        map.put("changeType", change.getChangeType().toString());
        map.put("category", change.getChangeCategory());
        map.put("entityIRI", change.getEntityIRI());
        map.put("entityLabel", change.getEntityLabel());
        map.put("description", change.getDescription());
        map.put("comment", change.getComment());
        map.put("oldValue", change.getOldValue());
        map.put("newValue", change.getNewValue());
        map.put("reverted", change.isReverted());
        
        if (change.isReverted()) {
            map.put("revertedBy", change.getRevertedBy());
            map.put("revertedAt", change.getRevertedAt().toString());
        }
        
        return map;
    }
}