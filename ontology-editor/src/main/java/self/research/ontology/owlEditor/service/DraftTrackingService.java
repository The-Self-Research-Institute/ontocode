package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.OntologyChange;
import self.research.ontology.owlEditor.repository.DraftChangeRepository;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Service for managing draft changes before they are committed to the ontology.
 * Tracks all editing operations and maintains them as drafts until explicitly saved.
 */
@Service
public class DraftTrackingService {
    
    private static final Logger log = LoggerFactory.getLogger(DraftTrackingService.class);
    
    // Project-level locks for draft operations to prevent race conditions
    private final Map<String, ReentrantLock> projectLocks = new ConcurrentHashMap<>();
    
    private final DraftChangeRepository draftRepository;
    private final OntologyMutationService mutationService;
    private final OntologyIndexService indexService;
    private final ProjectMetadataService metadataService;
    private final ChangeTrackingService changeTrackingService;
    private final GraphDBHistoryService graphDBHistoryService;
    private final Executor metadataExecutor;
    private final CollaborativeEditService collaborativeEditService;
    
    public DraftTrackingService(DraftChangeRepository draftRepository,
                               OntologyMutationService mutationService,
                               OntologyIndexService indexService,
                               ProjectMetadataService metadataService,
                               ChangeTrackingService changeTrackingService,
                               GraphDBHistoryService graphDBHistoryService,
                               @Qualifier("metadataExecutor") Executor metadataExecutor,
                               CollaborativeEditService collaborativeEditService) {
        this.draftRepository = draftRepository;
        this.mutationService = mutationService;
        this.indexService = indexService;
        this.metadataService = metadataService;
        this.changeTrackingService = changeTrackingService;
        this.graphDBHistoryService = graphDBHistoryService;
        this.metadataExecutor = metadataExecutor;
        this.collaborativeEditService = collaborativeEditService;
    }
    
    /**
     * Record a draft change without applying it to GraphDB
     */
    public DraftChange recordDraft(String projectId, String userId, String username,
                                   String operationType, Map<String, Object> operationData,
                                   String sessionId) {
        log.info("[DRAFT] Recording draft for project {}: {} by {}", projectId, operationType, username);
        
        DraftChange draft = new DraftChange(projectId, userId, username, operationType, operationData);
        draft.setSessionId(sessionId);
        
        return draftRepository.save(draft);
    }
    
    /**
     * Record multiple draft operations
     */
    public List<DraftChange> recordDrafts(String projectId, String userId, String username,
                                         List<MutationOp> operations, String sessionId) {
        log.info("[DRAFT] Recording {} draft operations for project {}", operations.size(), projectId);
        
        List<DraftChange> drafts = operations.stream()
            .map(op -> {
                Map<String, Object> data = new HashMap<>();
                data.put("type", op.type());
                data.put("iri", op.iri());
                if (op.label() != null) data.put("label", op.label());
                if (op.parent() != null) data.put("parent", op.parent());
                if (op.property() != null) data.put("property", op.property());
                if (op.value() != null) data.put("value", op.value());
                if (op.target() != null) data.put("target", op.target());
                if (op.classIri() != null) data.put("classIri", op.classIri());
                if (op.oldValue() != null) data.put("oldValue", op.oldValue());
                
                log.info("[DRAFT CREATION] operationType: {}, iri: {}, value: '{}', oldValue: '{}'", 
                    op.type(), op.iri(), op.value(), op.oldValue());
                
                return new DraftChange(projectId, userId, username, op.type(), data);
            })
            .peek(draft -> draft.setSessionId(sessionId))
            .collect(Collectors.toList());
        
        return draftRepository.saveAll(drafts);
    }
    
    /**
     * Get all unapplied drafts for a project
     */
    public List<DraftChange> getUnappliedDrafts(String projectId) {
        return draftRepository.findByProjectIdAndAppliedFalseOrderByTimestampAsc(projectId);
    }
    
    /**
     * Get all drafts (applied and unapplied) for a project
     */
    public List<DraftChange> getAllDrafts(String projectId) {
        return draftRepository.findByProjectIdOrderByTimestampDesc(projectId);
    }
    
    /**
     * Get draft count for a project
     */
    public long getDraftCount(String projectId) {
        return draftRepository.countByProjectIdAndAppliedFalse(projectId);
    }
    
    /**
     * Get or create a lock for a specific project
     */
    private ReentrantLock getProjectLock(String projectId) {
        return projectLocks.computeIfAbsent(projectId, k -> new ReentrantLock());
    }
    
    /**
     * Apply all unapplied drafts to GraphDB and mark them as applied
     * Uses project-level locking to prevent race conditions
     */
    public ApplyDraftsResult applyDrafts(String projectId) {
        log.info("[DRAFT] Applying drafts for project {}", projectId);
        
        ReentrantLock lock = getProjectLock(projectId);
        
        // Try to acquire lock with timeout to avoid deadlocks
        boolean lockAcquired = false;
        try {
            lockAcquired = lock.tryLock(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!lockAcquired) {
                log.warn("[DRAFT] Could not acquire lock for project {} - another operation in progress", projectId);
                return new ApplyDraftsResult(false, 0, "Another save operation is in progress. Please try again.");
            }
            
            return applyDraftsInternal(projectId);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[DRAFT] Interrupted while waiting for lock on project {}", projectId);
            return new ApplyDraftsResult(false, 0, "Operation interrupted");
        } finally {
            if (lockAcquired) {
                lock.unlock();
            }
        }
    }
    
    /**
     * Internal method to apply drafts (called within lock)
     */
    private ApplyDraftsResult applyDraftsInternal(String projectId) {
        List<DraftChange> unappliedDrafts = getUnappliedDrafts(projectId);
        
        if (unappliedDrafts.isEmpty()) {
            log.info("[DRAFT] No drafts to apply for project {}", projectId);
            return new ApplyDraftsResult(true, 0, "No drafts to apply");
        }
        
        try {
            // Convert drafts to mutation operations
            List<MutationOp> operations = unappliedDrafts.stream()
                .map(this::draftToMutationOp)
                .collect(Collectors.toList());
            
            // Log each operation for debugging
            log.info("[DRAFT] Converting {} drafts to mutation operations:", unappliedDrafts.size());
            for (MutationOp op : operations) {
                log.info("[DRAFT]   Operation: type={}, iri={}, label={}, parent={}", 
                    op.type(), op.iri(), op.label(), op.parent());
            }
            
            // Apply all mutations to GraphDB
            mutationService.apply(projectId, operations);

            // Broadcast each applied draft to collaborative clients
            unappliedDrafts.forEach(draft -> {
                MutationOp op = draftToMutationOp(draft);
                collaborativeEditService.broadcastMutation(
                    projectId,
                    op,
                    draft.getUserId(),
                    draft.getUsername()
                );
            });
            
            // Record changes to change tracking for Recent Activity
            recordDraftsAsChanges(projectId, unappliedDrafts);
            
            // Mark all drafts as applied
            unappliedDrafts.forEach(draft -> draft.setApplied(true));
            draftRepository.saveAll(unappliedDrafts);
            
            log.info("[DRAFT] Successfully applied {} drafts for project {}", unappliedDrafts.size(), projectId);
            
            // Update metadata asynchronously
            CompletableFuture.runAsync(() -> {
                Map<String, Object> meta = indexService.computeMetadata(projectId);
                metadataService.writeMeta(projectId, meta);
            }, metadataExecutor);
            
            return new ApplyDraftsResult(true, unappliedDrafts.size(), 
                "Successfully applied " + unappliedDrafts.size() + " draft changes");
            
        } catch (Exception e) {
            log.error("[DRAFT] Failed to apply drafts for project {}", projectId, e);
            return new ApplyDraftsResult(false, 0, "Failed to apply drafts: " + e.getMessage());
        }
    }
    
    /**
     * Discard all unapplied drafts for a project
     */
    public DiscardDraftsResult discardDrafts(String projectId) {
        log.info("[DRAFT] Discarding drafts for project {}", projectId);
        
        List<DraftChange> unappliedDrafts = getUnappliedDrafts(projectId);
        int count = unappliedDrafts.size();
        
        // Delete unapplied drafts
        unappliedDrafts.forEach(draft -> draftRepository.deleteById(draft.getId()));
        
        log.info("[DRAFT] Discarded {} drafts for project {}", count, projectId);
        
        return new DiscardDraftsResult(true, count, "Discarded " + count + " draft changes");
    }
    
    /**
     * Clear all applied drafts (cleanup)
     */
    public void clearAppliedDrafts(String projectId) {
        log.info("[DRAFT] Clearing applied drafts for project {}", projectId);
        draftRepository.deleteByProjectIdAndAppliedTrue(projectId);
    }
    
    /**
     * Get draft statistics
     */
    public Map<String, Object> getDraftStatistics(String projectId) {
        List<DraftChange> allDrafts = getAllDrafts(projectId);
        long unappliedCount = allDrafts.stream().filter(d -> !d.isApplied()).count();
        long appliedCount = allDrafts.stream().filter(DraftChange::isApplied).count();
        
        Map<String, Long> operationTypeCounts = allDrafts.stream()
            .filter(d -> !d.isApplied())
            .collect(Collectors.groupingBy(DraftChange::getOperationType, Collectors.counting()));
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalDrafts", allDrafts.size());
        stats.put("unappliedDrafts", unappliedCount);
        stats.put("appliedDrafts", appliedCount);
        stats.put("operationTypeCounts", operationTypeCounts);
        
        if (!allDrafts.isEmpty()) {
            stats.put("oldestDraft", allDrafts.get(allDrafts.size() - 1).getTimestamp());
            stats.put("newestDraft", allDrafts.get(0).getTimestamp());
        }
        
        return stats;
    }
    
    /**
     * Record drafts as permanent changes in change tracking
     */
    private void recordDraftsAsChanges(String projectId, List<DraftChange> drafts) {
        try {
            for (DraftChange draft : drafts) {
                OntologyChange.ChangeType changeType = mapOperationToChangeType(draft.getOperationType());
                if (changeType == null) continue;
                
                Map<String, Object> data = draft.getOperationData();
                String entityIri = (String) data.get("iri");
                String label = (String) data.get("label");
                String oldValue = data.get("oldValue") != null ? data.get("oldValue").toString() : null;
                String newValue = data.get("value") != null ? data.get("value").toString() : 
                                  (data.get("newValue") != null ? data.get("newValue").toString() : null);
                
                log.info("[DRAFT] Recording change - operationType: {}, oldValue: '{}', newValue: '{}', entityIRI: {}", 
                    draft.getOperationType(), oldValue, newValue, entityIri);
                log.info("[DRAFT] Operation data keys: {}", data.keySet());
                
                OntologyChange change = new OntologyChange.Builder(
                    projectId, 
                    draft.getUserId(), 
                    draft.getUsername(), 
                    changeType
                )
                .changeCategory(determineCategory(draft.getOperationType()))
                .entityIRI(entityIri)
                .entityLabel(label != null ? label : entityIri)
                .description(formatChangeDescription(draft))
                .sessionId(draft.getSessionId())
                .oldValue(oldValue)
                .newValue(newValue)
                .build();
                
                // Record to MongoDB via change tracking service
                changeTrackingService.recordChange(change);
                
                // Also record to GraphDB history for Change Assistant plugin
                String annotationProperty = data.get("property") != null ? data.get("property").toString() : null;
                graphDBHistoryService.recordEdit(
                    projectId,
                    draft.getUserId(),
                    draft.getUsername(),
                    draft.getOperationType(),
                    entityIri,
                    label != null ? label : entityIri,
                    oldValue,
                    newValue,
                    formatChangeDescription(draft),
                    annotationProperty
                );
            }
            log.info("[DRAFT] Recorded {} changes to change tracking and GraphDB history", drafts.size());
        } catch (Exception e) {
            log.error("[DRAFT] Failed to record changes to change tracking", e);
            // Don't fail the save if change tracking fails
        }
    }
    
    /**
     * Map operation type to ChangeType
     */
    private OntologyChange.ChangeType mapOperationToChangeType(String operationType) {
        return switch (operationType) {
            case "createClass" -> OntologyChange.ChangeType.ADD_CLASS;
            case "deleteClass" -> OntologyChange.ChangeType.REMOVE_CLASS;
            case "updateClassLabel" -> OntologyChange.ChangeType.RENAME_CLASS;
            case "createIndividual" -> OntologyChange.ChangeType.ADD_INDIVIDUAL;
            case "deleteIndividual" -> OntologyChange.ChangeType.REMOVE_INDIVIDUAL;
            case "createObjectProperty" -> OntologyChange.ChangeType.ADD_OBJECT_PROPERTY;
            case "deleteObjectProperty" -> OntologyChange.ChangeType.REMOVE_OBJECT_PROPERTY;
            case "createDataProperty" -> OntologyChange.ChangeType.ADD_DATA_PROPERTY;
            case "deleteDataProperty" -> OntologyChange.ChangeType.REMOVE_DATA_PROPERTY;
            case "addAnnotation" -> OntologyChange.ChangeType.ADD_ANNOTATION;
            case "updateAnnotation" -> OntologyChange.ChangeType.MODIFY_ANNOTATION;
            case "deleteAnnotation" -> OntologyChange.ChangeType.REMOVE_ANNOTATION;
            case "addSubClassOf" -> OntologyChange.ChangeType.ADD_SUBCLASS;
            case "deleteSubClassOf" -> OntologyChange.ChangeType.REMOVE_SUBCLASS;
            case "addEquivalentClass", "deleteEquivalentClass", "addDisjointWith", "deleteDisjointWith" -> OntologyChange.ChangeType.ADD_AXIOM;
            case "addPropertyDomain" -> OntologyChange.ChangeType.ADD_DOMAIN;
            case "deletePropertyDomain" -> OntologyChange.ChangeType.REMOVE_DOMAIN;
            case "addPropertyRange" -> OntologyChange.ChangeType.ADD_RANGE;
            case "deletePropertyRange" -> OntologyChange.ChangeType.REMOVE_RANGE;
            case "addSubPropertyOf", "deleteSubPropertyOf" -> OntologyChange.ChangeType.ADD_AXIOM;
            default -> OntologyChange.ChangeType.OTHER;
        };
    }
    
    /**
     * Determine change category from operation type
     */
    private String determineCategory(String operationType) {
        if (operationType.contains("Class")) return "CLASS";
        if (operationType.contains("Individual")) return "INDIVIDUAL";
        if (operationType.contains("Property")) return "PROPERTY";
        if (operationType.contains("Annotation")) return "ANNOTATION";
        if (operationType.contains("Axiom")) return "AXIOM";
        return "OTHER";
    }
    
    /**
     * Format change description
     */
    private String formatChangeDescription(DraftChange draft) {
        Map<String, Object> data = draft.getOperationData();
        String label = (String) data.get("label");
        String iri = (String) data.get("iri");
        String displayName = label != null ? label : iri;
        
        return switch (draft.getOperationType()) {
            case "createClass" -> "Created class: " + displayName;
            case "deleteClass" -> "Deleted class: " + displayName;
            case "updateClassLabel" -> "Updated label: " + displayName;
            case "createIndividual" -> "Created individual: " + displayName;
            case "deleteIndividual" -> "Deleted individual: " + displayName;
            case "createObjectProperty" -> "Created property: " + displayName;
            case "deleteObjectProperty" -> "Deleted property: " + displayName;
            case "createDataProperty" -> "Created data property: " + displayName;
            case "deleteDataProperty" -> "Deleted data property: " + displayName;
            case "createAnnotationProperty" -> "Created annotation property: " + displayName;
            case "deleteAnnotationProperty" -> "Deleted annotation property: " + displayName;
            case "addAnnotation" -> "Added annotation to: " + displayName;
            case "updateAnnotation" -> "Updated annotation for: " + displayName;
            case "deleteAnnotation" -> "Removed annotation from: " + displayName;
            case "addSubClassOf" -> "Added subclass axiom for: " + displayName;
            case "deleteSubClassOf" -> "Removed subclass axiom from: " + displayName;
            case "addEquivalentClass" -> "Added equivalent class for: " + displayName;
            case "deleteEquivalentClass" -> "Removed equivalent class from: " + displayName;
            case "addDisjointWith" -> "Added disjoint axiom for: " + displayName;
            case "deleteDisjointWith" -> "Removed disjoint axiom from: " + displayName;
            default -> "Modified: " + displayName;
        };
    }
    
    /**
     * Convert DraftChange to MutationOp
     */
    private MutationOp draftToMutationOp(DraftChange draft) {
        Map<String, Object> data = draft.getOperationData();
        
        // Handle cardinality conversion
        Integer cardinality = null;
        Object cardObj = data.get("cardinality");
        if (cardObj instanceof Number) {
            cardinality = ((Number) cardObj).intValue();
        } else if (cardObj instanceof String) {
            try {
                cardinality = Integer.parseInt((String) cardObj);
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        
        return new MutationOp(
            draft.getOperationType(),
            (String) data.get("iri"),
            (String) data.get("label"),
            (String) data.get("parent"),
            (String) data.get("property"),
            (String) data.get("value"),
            (String) data.get("target"),
            (String) data.get("classIri"),
            (String) data.get("restrictionType"),
            cardinality,
            (String) data.get("axiomType"),
            (String) data.get("oldValue")
        );
    }
    
    // Result classes
    
    public static class ApplyDraftsResult {
        private final boolean success;
        private final int appliedCount;
        private final String message;
        
        public ApplyDraftsResult(boolean success, int appliedCount, String message) {
            this.success = success;
            this.appliedCount = appliedCount;
            this.message = message;
        }
        
        public boolean isSuccess() { return success; }
        public int getAppliedCount() { return appliedCount; }
        public String getMessage() { return message; }
    }
    
    public static class DiscardDraftsResult {
        private final boolean success;
        private final int discardedCount;
        private final String message;
        
        public DiscardDraftsResult(boolean success, int discardedCount, String message) {
            this.success = success;
            this.discardedCount = discardedCount;
            this.message = message;
        }
        
        public boolean isSuccess() { return success; }
        public int getDiscardedCount() { return discardedCount; }
        public String getMessage() { return message; }
    }
}
