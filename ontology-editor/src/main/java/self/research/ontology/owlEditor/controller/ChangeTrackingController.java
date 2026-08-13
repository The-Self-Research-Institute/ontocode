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
import self.research.ontology.owlEditor.service.OntologyHistoryService;
import self.research.ontology.owlEditor.service.HistorySyncService;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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
    private self.research.ontology.owlEditor.service.OntologyMutationService ontologyMutationService;

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

    @GetMapping("/{projectId}/changes/recent")
    public ResponseEntity<Map<String, Object>> getRecentChanges(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "100") int count
    ) {
        try {

            List<HistoryChange> historyChanges = historySyncService.getHistoryChanges(projectId);

            if (historyChanges.size() > count) {
                historyChanges = historyChanges.subList(0, count);
            }

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

        int commentCount = change.getComments() != null ? change.getComments().size() : 0;
        map.put("commentCount", commentCount);

        return map;
    }

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
            @RequestBody Map<String, Object> request
    ) {
        String changeId = (String) request.get("changeId");
        if (changeId == null) {
            changeId = "unknown";
        }
        return performRollback(projectId, changeId, request);
    }

    @PostMapping("/{projectId}/changes/{changeId}/rollback")
    public ResponseEntity<Map<String, Object>> rollbackChange(
            @PathVariable String projectId,
            @PathVariable String changeId,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        return performRollback(projectId, changeId, request);
    }

    private ResponseEntity<Map<String, Object>> performRollback(
            String projectId,
            String changeId,
            Map<String, Object> request
    ) {
        try {
            log.info("[ROLLBACK] Starting rollback for change {} in project {}", changeId, projectId);

            HistoryChange historyChange = historySyncService.getHistoryChange(changeId);

            String action = null;
            String entityIRI = null;
            String entityLabel = null;
            String oldValue = null;
            String newValue = null;
            String changeType = null;
            String annotationProperty = null;
            String userId = "system";
            String username = "System";

            if (historyChange != null) {
                log.info("[ROLLBACK] Found change in MongoDB: {}", historyChange.getId());
                action = mapOperationToAction(historyChange.getOperationType());
                entityIRI = historyChange.getEntityIRI();
                entityLabel = historyChange.getEntityLabel();
                oldValue = historyChange.getOldValue();
                newValue = historyChange.getNewValue();
                changeType = historyChange.getEntityType();
                annotationProperty = historyChange.getAnnotationProperty();
                log.info("[ROLLBACK] MongoDB data - action: {}, entityIRI: {}, changeType: {}, annotationProperty: {}",
                    action, entityIRI, changeType, annotationProperty);
            }

            if (request != null) {
                if (request.get("action") != null) action = (String) request.get("action");
                if (request.get("entityIRI") != null) entityIRI = (String) request.get("entityIRI");
                if (request.get("entityLabel") != null) entityLabel = (String) request.get("entityLabel");
                if (request.get("oldValue") != null) oldValue = (String) request.get("oldValue");
                if (request.get("newValue") != null) newValue = (String) request.get("newValue");
                if (request.get("changeType") != null) changeType = (String) request.get("changeType");
                if (request.get("annotationProperty") != null) annotationProperty = (String) request.get("annotationProperty");
                if (request.get("userId") != null) userId = (String) request.get("userId");
                if (request.get("username") != null) username = (String) request.get("username");
            }

            log.info("[ROLLBACK] Final values - action: {}, entityIRI: {}, changeType: {}, entityLabel: {}",
                action, entityIRI, changeType, entityLabel);
            log.info("[ROLLBACK] oldValue: '{}', newValue: '{}'", oldValue, newValue);

            if (entityIRI == null || entityIRI.trim().isEmpty() || "null".equalsIgnoreCase(entityIRI.trim())) {
                log.error("[ROLLBACK] entityIRI is null, empty, or 'null' string: '{}'", entityIRI);
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Entity IRI is required for rollback (received: " + entityIRI + ")"
                ));
            }

            if (action == null || action.trim().isEmpty()) {
                log.error("[ROLLBACK] action is null or empty");
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Action is required for rollback"
                ));
            }

            try {

                List<self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp> inverseMutations =
                    createInverseMutation(action, changeType, entityIRI, entityLabel, oldValue, newValue, annotationProperty);

                boolean mutationApplied = false;
                if (!inverseMutations.isEmpty()) {
                    log.info("[ROLLBACK] Applying {} inverse mutations to GraphDB", inverseMutations.size());
                    ontologyMutationService.apply(projectId, inverseMutations);
                    mutationApplied = true;

                    log.info("[ROLLBACK] Broadcasting rollback event to /topic/ontology/{}", projectId);
                    Map<String, Object> rollbackEvent = new HashMap<>();
                    rollbackEvent.put("type", "ROLLBACK");
                    rollbackEvent.put("projectId", projectId);
                    rollbackEvent.put("changeId", changeId);
                    rollbackEvent.put("action", action);
                    rollbackEvent.put("changeType", changeType);
                    rollbackEvent.put("entityIRI", entityIRI);
                    rollbackEvent.put("entityLabel", entityLabel);
                    rollbackEvent.put("userId", userId);
                    rollbackEvent.put("username", username);
                    rollbackEvent.put("oldValue", oldValue);
                    rollbackEvent.put("newValue", newValue);
                    rollbackEvent.put("timestamp", System.currentTimeMillis());
                    messagingTemplate.convertAndSend("/topic/ontology/" + projectId, rollbackEvent);
                } else {
                    log.info("[ROLLBACK] No inverse mutations created (unsupported change type), will only record in history");
                }

                boolean mongoSuccess = false;
                if (historyChange != null) {
                    mongoSuccess = changeTrackingService.revertChange(changeId, userId, username);
                }

                historyService.recordEdit(
                    projectId,
                    userId,
                    username,
                    "ROLLBACK_" + action.toUpperCase(),
                    entityIRI,
                    entityLabel,
                    newValue,
                    oldValue,
                    "Rolled back: " + (changeType != null ? changeType : "change") + " on " + (entityLabel != null ? entityLabel : entityIRI)
                );

                log.info("[ROLLBACK] Successfully rolled back change {}", changeId);
                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", mutationApplied ? "Change rolled back successfully" : "Rollback recorded (no mutation applied for this change type)",
                    "changeId", changeId,
                    "mutationApplied", mutationApplied,
                    "mongoUpdated", mongoSuccess,
                    "entityIRI", entityIRI,
                    "entityLabel", oldValue != null ? oldValue : entityLabel
                ));
            } catch (Exception e) {
                log.error("[ROLLBACK] Failed to apply inverse mutation", e);
                return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to apply inverse mutation: " + e.getMessage(),
                    "changeId", changeId
                ));
            }

        } catch (Exception e) {
            log.error("[ROLLBACK] Error rolling back change", e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    private String mapOperationToAction(String operationType) {
        if (operationType == null) return "modified";
        String lower = operationType.toLowerCase();
        if (lower.contains("create") || lower.contains("add") || lower.contains("insert")) return "added";
        if (lower.contains("delete") || lower.contains("remove")) return "deleted";
        return "modified";
    }

    private List<self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp> createInverseMutation(
            String action, String changeType, String entityIRI, String entityLabel, String oldValue, String newValue, String annotationProperty) {

        List<self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp> mutations = new ArrayList<>();

        log.info("[ROLLBACK] Creating inverse mutation - action: {}, changeType: {}, entityIRI: {}, annotationProperty: {}",
            action, changeType, entityIRI, annotationProperty);
        log.info("[ROLLBACK] oldValue: '{}', newValue: '{}', entityLabel: '{}'", oldValue, newValue, entityLabel);

        String actionLower = action != null ? action.toLowerCase() : "";
        String typeLower = changeType != null ? changeType.toLowerCase() : "";

        boolean hasOldAndNewValue = oldValue != null && !oldValue.isEmpty() && newValue != null && !newValue.isEmpty();

        log.info("[ROLLBACK] actionLower: '{}', typeLower: '{}', hasOldAndNewValue: {}", actionLower, typeLower, hasOldAndNewValue);

        switch (actionLower) {
            case "added":

                if (typeLower.contains("class") && !typeLower.contains("annotation")) {
                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "deleteClass", entityIRI, null, null, null, null, null, null, null, null, null, null, null, null, null));
                } else if (typeLower.contains("objectproperty") || typeLower.contains("object_property")) {
                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "deleteObjectProperty", entityIRI, null, null, null, null, null, null, null, null, null, null, null, null, null));
                } else if (typeLower.contains("dataproperty") || typeLower.contains("data_property") || typeLower.contains("datatypeproperty")) {
                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "deleteDataProperty", entityIRI, null, null, null, null, null, null, null, null, null, null, null, null, null));
                } else if (typeLower.contains("annotationproperty") || typeLower.contains("annotation_property")) {
                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "deleteAnnotationProperty", entityIRI, null, null, null, null, null, null, null, null, null, null, null, null, null));
                } else if (typeLower.contains("property") && !typeLower.contains("annotation")) {

                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "deleteObjectProperty", entityIRI, null, null, null, null, null, null, null, null, null, null, null, null, null));
                } else if (typeLower.contains("individual")) {
                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "deleteIndividual", entityIRI, null, null, null, null, null, null, null, null, null, null, null, null, null));
                } else if (typeLower.contains("annotation") || typeLower.contains("label") || typeLower.contains("comment")) {

                    String annotationProp = determineAnnotationProperty(typeLower);
                    if (newValue != null && !newValue.isEmpty()) {
                        mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                            "deleteAnnotation", entityIRI, null, null, annotationProp, newValue, null, null, null, null, null, null, null, null, null));
                    }
                } else {
                    log.warn("[ROLLBACK] Unknown type for 'added' action: {}, skipping mutation", changeType);
                }
                break;

            case "deleted":

                if (typeLower.contains("class") && !typeLower.contains("annotation")) {

                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "createClass", entityIRI, entityLabel != null ? entityLabel : extractLabel(entityIRI),
                        "http://www.w3.org/2002/07/owl#Thing", null, null, null, null, null, null, null, null, null, null, null));
                } else if (typeLower.contains("objectproperty") || typeLower.contains("object_property")) {

                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "createObjectProperty", entityIRI, entityLabel, null, null, null, null, null, null, null, null, null, null, null, null));
                } else if (typeLower.contains("dataproperty") || typeLower.contains("data_property") || typeLower.contains("datatypeproperty")) {
                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "createDataProperty", entityIRI, entityLabel, null, null, null, null, null, null, null, null, null, null, null, null));
                } else if (typeLower.contains("annotationproperty") || typeLower.contains("annotation_property")) {
                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "createAnnotationProperty", entityIRI, entityLabel, null, null, null, null, null, null, null, null, null, null, null, null));
                } else if (typeLower.contains("property") && !typeLower.contains("annotation")) {

                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "createObjectProperty", entityIRI, entityLabel, null, null, null, null, null, null, null, null, null, null, null, null));
                } else if (typeLower.contains("individual")) {

                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "createIndividual", entityIRI, entityLabel, null, null, null, null, "http://www.w3.org/2002/07/owl#Thing", null, null, null, null, null, null, null));
                } else if (typeLower.contains("annotation") || typeLower.contains("label") || typeLower.contains("comment")) {

                    String annotationProp = determineAnnotationProperty(typeLower);
                    if (oldValue != null && !oldValue.isEmpty()) {
                        mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                            "addAnnotation", entityIRI, null, null, annotationProp, oldValue, null, null, null, null, null, null, null, null, null));
                    }
                } else {
                    log.warn("[ROLLBACK] Unknown type for 'deleted' action: {}, skipping mutation", changeType);
                }
                break;

            case "modified":

                if (hasOldAndNewValue) {

                    String propertyToUse = annotationProperty != null && !annotationProperty.isEmpty()
                        ? annotationProperty
                        : "http://www.w3.org/2000/01/rdf-schema#label";
                    log.info("[ROLLBACK] Detected label/annotation change, reverting property '{}' to oldValue: '{}' from newValue: '{}'",
                        propertyToUse, oldValue, newValue);
                    mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                        "updateAnnotation", entityIRI, null, null, propertyToUse, oldValue, null, null, null, null, null, newValue, null, null, null));
                } else if (typeLower.contains("label")) {

                    if (oldValue != null && !oldValue.isEmpty()) {
                        mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                            "updateAnnotation", entityIRI, null, null, "http://www.w3.org/2000/01/rdf-schema#label", oldValue, null, null, null, null, null, newValue, null, null, null));
                    }
                } else if (typeLower.contains("comment")) {

                    if (oldValue != null && !oldValue.isEmpty()) {
                        mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                            "updateAnnotation", entityIRI, null, null, "http://www.w3.org/2000/01/rdf-schema#comment", oldValue, null, null, null, null, null, newValue, null, null, null));
                    }
                } else if (typeLower.contains("annotation")) {

                    String annotationProp = annotationProperty != null && !annotationProperty.isEmpty()
                        ? annotationProperty
                        : determineAnnotationProperty(typeLower);
                    if (oldValue != null && !oldValue.isEmpty()) {
                        mutations.add(new self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp(
                            "updateAnnotation", entityIRI, null, null, annotationProp, oldValue, null, null, null, null, null, newValue, null, null, null));
                    }
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

    private String determineAnnotationProperty(String changeType) {
        if (changeType == null) return "http://www.w3.org/2000/01/rdf-schema#label";
        String lower = changeType.toLowerCase();
        if (lower.contains("comment")) return "http://www.w3.org/2000/01/rdf-schema#comment";
        if (lower.contains("seealso")) return "http://www.w3.org/2000/01/rdf-schema#seeAlso";
        if (lower.contains("isdefinedby")) return "http://www.w3.org/2000/01/rdf-schema#isDefinedBy";

        return "http://www.w3.org/2000/01/rdf-schema#label";
    }

    private String extractLabel(String iri) {
        if (iri == null) return "Unknown";
        String[] parts = iri.split("[#/]");
        return parts.length > 0 ? parts[parts.length - 1] : "Unknown";
    }

    @GetMapping("/{projectId}/changes/{changeId}/details")
    public ResponseEntity<Map<String, Object>> getChangeDetails(
            @PathVariable String projectId,
            @PathVariable String changeId
    ) {
        try {

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

                commentsList.sort((a, b) -> {
                    String tsA = (String) a.get("timestamp");
                    String tsB = (String) b.get("timestamp");
                    if (tsA == null && tsB == null) return 0;
                    if (tsA == null) return 1;
                    if (tsB == null) return -1;
                    return tsA.compareTo(tsB);
                });
                details.put("comments", commentsList);

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