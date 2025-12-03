package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.model.OntologyChange;
import self.research.ontology.owlEditor.service.ChangeTrackingService;
import self.research.ontology.owlEditor.service.GraphDBHistoryService;

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
    private SimpMessagingTemplate messagingTemplate;

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