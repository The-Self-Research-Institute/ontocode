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
import self.research.ontology.owlEditor.model.RollbackAudit;
import self.research.ontology.owlEditor.repository.RollbackAuditRepository;
import self.research.ontology.owlEditor.service.ChangeTrackingService;
import self.research.ontology.owlEditor.service.OntologyHistoryService;
import self.research.ontology.owlEditor.service.ChangeRollbackService;
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
    private OntologyHistoryService historyService;
    
    @Autowired
    private HistorySyncService historySyncService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    @Autowired
    private self.research.ontology.owlEditor.service.ChangeRollbackService rollbackService;

    @Autowired
    private RollbackRequestSupport rollbackSupport;

    @Autowired
    private RollbackAuditRepository rollbackAuditRepository;

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
     * Get recent changes - Uses MongoDB as the single source of truth
     * GET /api/ontology/{projectId}/changes/recent
     */
    @GetMapping("/{projectId}/changes/recent")
    public ResponseEntity<Map<String, Object>> getRecentChanges(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "100") int count
    ) {
        try {
            // Use MongoDB as the single source for change tracking
            List<HistoryChange> historyChanges = historySyncService.getHistoryChanges(projectId);
            
            // Limit results
            if (historyChanges.size() > count) {
                historyChanges = historyChanges.subList(0, count);
            }
            
            // Convert to response format
            List<Map<String, Object>> changes = historyChanges.stream()
                .map(this::historyChangeToMap)
                .collect(Collectors.toList());
            
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
     * Convert HistoryChange to Map for API response
     */
    private Map<String, Object> historyChangeToMap(HistoryChange change) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", change.getId());
        map.put("editId", change.getEditId());
        map.put("timestamp", change.getTimestamp() != null ? change.getTimestamp().toString() : null);
        map.put("userId", change.getUserId());
        map.put("username", change.getUsername());
        map.put("changeType", change.getOperationType());
        map.put("operationType", change.getOperationType());
        map.put("entityType", change.getEntityType());
        map.put("changeCategory", change.getEntityType());
        map.put("entityIRI", change.getEntityIRI());
        map.put("entityLabel", change.getEntityLabel());
        map.put("oldValue", change.getOldValue());
        map.put("newValue", change.getNewValue());
        map.put("description", change.getDescription());
        map.put("status", change.getStatus());
        map.put("hasConflict", change.isHasConflict());
        putChangeSetFields(map, change);
        map.put("reverted", change.isReverted());
        if (change.isReverted()) {
            Map<String, Object> auditInfo = resolveAuditInfo(change.getRevertedAuditId());
            map.put("revertedBy", auditInfo.get("revertedBy"));
            map.put("revertedAt", auditInfo.get("revertedAt"));
            map.put("revertedAuditId", change.getRevertedAuditId());
            map.put("revertedWithSet", auditInfo.get("revertedWithSet"));
        }

        List<Map<String, Object>> subChangeMaps = new ArrayList<>();
        if (change.getSubChanges() != null) {
            for (HistoryChange.SubChange sc : change.getSubChanges()) {
                Map<String, Object> scMap = new HashMap<>();
                scMap.put("id", sc.getId());
                scMap.put("predicate", sc.getPredicate());
                scMap.put("oldValue", sc.getOldValue());
                scMap.put("newValue", sc.getNewValue());
                scMap.put("annotationProperty", sc.getAnnotationProperty());
                scMap.put("addition", sc.isAddition());
                scMap.put("reverted", sc.isReverted());
                if (sc.isReverted()) {
                    Map<String, Object> auditInfo = resolveAuditInfo(sc.getRevertedAuditId());
                    scMap.put("revertedBy", auditInfo.get("revertedBy"));
                    scMap.put("revertedAt", auditInfo.get("revertedAt"));
                    scMap.put("revertedAuditId", sc.getRevertedAuditId());
                    scMap.put("revertedWithSet", auditInfo.get("revertedWithSet"));
                }
                subChangeMaps.add(scMap);
            }
        }
        map.put("subChanges", subChangeMaps);

        // Include comments count
        int commentCount = change.getComments() != null ? change.getComments().size() : 0;
        map.put("commentCount", commentCount);

        return map;
    }

    /**
     * Resolve who/when a rollback happened from its RollbackAudit doc.
     * Returns an empty-valued map (never null) if the audit entry can't be found.
     */
    private static void putChangeSetFields(Map<String, Object> map, HistoryChange change) {
        map.put("changeSetId", change.getChangeSetId());
        map.put("source", change.getSource());
        map.put("draft", change.isDraft());
        if (change.getAi() != null) {
            Map<String, Object> ai = new HashMap<>();
            ai.put("provider", change.getAi().getProvider());
            ai.put("model", change.getAi().getModel());
            ai.put("sessionId", change.getAi().getSessionId());
            ai.put("groupId", change.getAi().getGroupId());
            ai.put("summary", change.getAi().getSummary());
            map.put("ai", ai);
        }
        if (change.getMetadata() != null && change.getMetadata().get("revertsChangeSetId") != null) {
            map.put("revertsChangeSetId", change.getMetadata().get("revertsChangeSetId"));
        }
        if (change.getMetadata() != null && change.getMetadata().get("rollbackAuditId") != null) {
            map.put("rollbackAuditId", change.getMetadata().get("rollbackAuditId"));
        }
    }

    private Map<String, Object> resolveAuditInfo(String auditId) {
        Map<String, Object> info = new HashMap<>();
        if (auditId != null) {
            rollbackAuditRepository.findById(auditId).ifPresent(audit -> {
                info.put("revertedBy", audit.getRevertedByUsername());
                info.put("revertedAt", audit.getRevertedAt() != null ? audit.getRevertedAt().toString() : null);
                info.put("revertedWithSet", audit.getChangeSetId() != null);
            });
        }
        return info;
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

    @PostMapping("/{projectId}/changes/rollback")
    public ResponseEntity<Map<String, Object>> rollbackChangeWithBody(
            @PathVariable String projectId,
            @RequestBody Map<String, Object> request,
            @RequestParam(defaultValue = "false") boolean dryRun,
            jakarta.servlet.http.HttpServletRequest httpRequest
    ) {
        String changeId = request != null && request.get("changeId") instanceof String id ? id : null;
        return undoEntry(projectId, changeId, request, dryRun, httpRequest);
    }

    @PostMapping("/{projectId}/changes/{changeId}/rollback")
    public ResponseEntity<Map<String, Object>> rollbackChange(
            @PathVariable String projectId,
            @PathVariable String changeId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestParam(defaultValue = "false") boolean dryRun,
            jakarta.servlet.http.HttpServletRequest httpRequest
    ) {
        return undoEntry(projectId, changeId, request, dryRun, httpRequest);
    }

    @PostMapping("/{projectId}/changes/{changeId}/subchanges/{subChangeId}/rollback")
    public ResponseEntity<Map<String, Object>> rollbackSubChange(
            @PathVariable String projectId,
            @PathVariable String changeId,
            @PathVariable String subChangeId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestParam(defaultValue = "false") boolean dryRun,
            jakarta.servlet.http.HttpServletRequest httpRequest
    ) {
        HistoryChange change = historySyncService.getHistoryChange(changeId);
        if (change == null) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Change not found"));
        }
        ResponseEntity<Map<String, Object>> denied = rollbackSupport.denyIfNotAllowed(List.of(change), projectId, httpRequest);
        if (denied != null) {
            return denied;
        }
        ChangeRollbackService.Result result = rollbackService.undoSubChange(projectId, change.getId(), subChangeId,
                rollbackSupport.actor(httpRequest, request), dryRun);
        return RollbackRequestSupport.toBody(result, changeId, subChangeId);
    }

    private ResponseEntity<Map<String, Object>> undoEntry(String projectId, String changeId, Map<String, Object> request,
                                                          boolean dryRun, jakarta.servlet.http.HttpServletRequest httpRequest) {
        if (changeId == null || changeId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "changeId is required"));
        }
        HistoryChange change = historySyncService.getHistoryChange(changeId);
        if (change == null) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Change not found"));
        }
        ResponseEntity<Map<String, Object>> denied = rollbackSupport.denyIfNotAllowed(List.of(change), projectId, httpRequest);
        if (denied != null) {
            return denied;
        }
        ChangeRollbackService.Result result = rollbackService.undoEntry(projectId, change.getId(),
                rollbackSupport.actor(httpRequest, request), dryRun);
        return RollbackRequestSupport.toBody(result, changeId, null);
    }

    /**
     * Get change details including comments
     * GET /api/ontology/{projectId}/changes/{changeId}/details
     */
    @GetMapping("/{projectId}/changes/{changeId}/details")
    public ResponseEntity<Map<String, Object>> getChangeDetails(
            @PathVariable String projectId,
            @PathVariable String changeId
    ) {
        try {
            // Try to find in MongoDB synced changes first (has comments)
            HistoryChange historyChange = historySyncService.getHistoryChange(changeId);
            
            if (historyChange != null) {
                Map<String, Object> details = new HashMap<>();
                details.put("id", historyChange.getId());
                details.put("projectId", historyChange.getProjectId());
                details.put("timestamp", historyChange.getTimestamp().toString());
                details.put("userId", historyChange.getUserId());
                details.put("username", historyChange.getUsername());
                details.put("operationType", historyChange.getOperationType());
                details.put("entityType", historyChange.getEntityType());
                details.put("entityIRI", historyChange.getEntityIRI());
                details.put("entityLabel", historyChange.getEntityLabel());
                details.put("oldValue", historyChange.getOldValue());
                details.put("newValue", historyChange.getNewValue());
                details.put("description", historyChange.getDescription());
                details.put("status", historyChange.getStatus());
                details.put("hasConflict", historyChange.isHasConflict());
                
                // Convert comments to list format
                List<Map<String, Object>> commentsList = new ArrayList<>();
                if (historyChange.getComments() != null) {
                    historyChange.getComments().forEach((commentId, comment) -> {
                        Map<String, Object> commentMap = new HashMap<>();
                        commentMap.put("id", commentId);
                        commentMap.put("userId", comment.getUserId());
                        commentMap.put("username", comment.getUsername());
                        commentMap.put("text", comment.getText());
                        commentMap.put("timestamp", comment.getTimestamp() != null ? comment.getTimestamp().toString() : null);
                        commentsList.add(commentMap);
                    });
                }
                // Sort comments by timestamp
                commentsList.sort((a, b) -> {
                    String tsA = (String) a.get("timestamp");
                    String tsB = (String) b.get("timestamp");
                    if (tsA == null && tsB == null) return 0;
                    if (tsA == null) return 1;
                    if (tsB == null) return -1;
                    return tsA.compareTo(tsB);
                });
                details.put("comments", commentsList);
                
                // Add approval/rejection info
                if (historyChange.getApprovedBy() != null) {
                    details.put("approvedBy", historyChange.getApprovedBy());
                    details.put("approvedAt", historyChange.getApprovedAt() != null ? historyChange.getApprovedAt().toString() : null);
                }
                if (historyChange.getRejectedBy() != null) {
                    details.put("rejectedBy", historyChange.getRejectedBy());
                    details.put("rejectedAt", historyChange.getRejectedAt() != null ? historyChange.getRejectedAt().toString() : null);
                }
                
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "change", details
                ));
            }
            
            return ResponseEntity.status(404).body(Map.of(
                "success", false,
                "error", "Change not found"
            ));
            
        } catch (Exception e) {
            log.error("Error getting change details", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
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