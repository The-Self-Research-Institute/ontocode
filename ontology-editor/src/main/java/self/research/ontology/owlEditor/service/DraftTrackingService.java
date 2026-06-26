package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.DraftCopyStatus;
import self.research.ontology.owlEditor.model.OntologyChange;
import self.research.ontology.owlEditor.model.merge.ConflictResolution;
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
    private final SparqlDatasetService datasetService;
    private final OntologyIndexService indexService;
    private final ProjectMetadataService metadataService;
    private final ChangeTrackingService changeTrackingService;
    private final OntologyHistoryService historyService;
    private final Executor metadataExecutor;
    private final CollaborativeEditService collaborativeEditService;
    private final DraftPublishService draftPublishService;
    private final MainGraphRevisionService mainGraphRevisionService;
    private final DraftPublishMergeService draftPublishMergeService;
    private final DraftCopyService draftCopyService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OntologySpringCacheEvictionService springCacheEviction;

    public DraftTrackingService(DraftChangeRepository draftRepository,
                               OntologyMutationService mutationService,
                               SparqlDatasetService datasetService,
                               OntologyIndexService indexService,
                               ProjectMetadataService metadataService,
                               ChangeTrackingService changeTrackingService,
                               OntologyHistoryService historyService,
                               @Qualifier("metadataExecutor") Executor metadataExecutor,
                               CollaborativeEditService collaborativeEditService,
                               DraftPublishService draftPublishService,
                               MainGraphRevisionService mainGraphRevisionService,
                               DraftPublishMergeService draftPublishMergeService,
                               DraftCopyService draftCopyService) {
        this.draftRepository = draftRepository;
        this.mutationService = mutationService;
        this.datasetService = datasetService;
        this.indexService = indexService;
        this.metadataService = metadataService;
        this.changeTrackingService = changeTrackingService;
        this.historyService = historyService;
        this.metadataExecutor = metadataExecutor;
        this.collaborativeEditService = collaborativeEditService;
        this.draftPublishService = draftPublishService;
        this.mainGraphRevisionService = mainGraphRevisionService;
        this.draftPublishMergeService = draftPublishMergeService;
        this.draftCopyService = draftCopyService;
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
                if (op.restrictionType() != null) data.put("restrictionType", op.restrictionType());
                if (op.cardinality() != null) data.put("cardinality", op.cardinality());
                if (op.axiomType() != null) data.put("axiomType", op.axiomType());
                if (op.oldValue() != null) data.put("oldValue", op.oldValue());
                if (op.language() != null) data.put("language", op.language());
                if (op.datatype() != null) data.put("datatype", op.datatype());
                
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

    public List<DraftChange> getUnappliedDraftsForUser(String projectId, String userId) {
        return draftRepository.findByProjectIdAndUserIdAndAppliedFalseOrderByTimestampAsc(projectId, userId);
    }

    public DraftPublishAnalysis analyzePublish(String projectId, String userId) {
        return analyzePublish(projectId, userId, false);
    }

    public DraftPublishAnalysis analyzePublish(String projectId, String userId, boolean enrichAxiomDetail) {
        List<DraftChange> userDrafts = getUnappliedDraftsForUser(projectId, userId);
        return draftPublishService.analyze(projectId, userId, userDrafts, enrichAxiomDetail);
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

    public ApplyDraftsResult applyDrafts(String projectId, String userId, boolean force) {
        return applyDrafts(projectId, userId, force, false, null);
    }

    public ApplyDraftsResult applyDrafts(String projectId, String userId, boolean force, boolean merge) {
        return applyDrafts(projectId, userId, force, merge, null);
    }

    /**
     * Apply unapplied drafts for a single user to the main graph.
     */
    public ApplyDraftsResult applyDrafts(String projectId, String userId, boolean force, boolean merge,
                                         Map<String, ConflictResolution> resolutions) {
        log.info("[DRAFT] Applying drafts for project {} user {} (force={}, merge={})",
                projectId, userId, force, merge);

        if (userId == null || userId.isBlank()) {
            return new ApplyDraftsResult(false, 0, "userId is required to publish drafts", true, null);
        }

        ReentrantLock lock = getProjectLock(projectId);

        boolean lockAcquired = false;
        try {
            lockAcquired = lock.tryLock(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!lockAcquired) {
                log.warn("[DRAFT] Could not acquire lock for project {} - another operation in progress", projectId);
                return new ApplyDraftsResult(false, 0, "Another save operation is in progress. Please try again.", false, null);
            }

            return applyDraftsInternal(projectId, userId, force, merge, resolutions);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[DRAFT] Interrupted while waiting for lock on project {}", projectId);
            return new ApplyDraftsResult(false, 0, "Operation interrupted", false, null);
        } finally {
            if (lockAcquired) {
                lock.unlock();
            }
        }
    }

    /**
     * @deprecated Use {@link #applyDrafts(String, String, boolean, boolean, Map)} to publish only one user's drafts.
     */
    @Deprecated
    public ApplyDraftsResult applyDrafts(String projectId) {
        log.warn("[DRAFT] applyDrafts(projectId) without userId is deprecated");
        return applyDrafts(projectId, "anonymous", false, false, null);
    }
    
    private ApplyDraftsResult applyDraftsInternal(String projectId, String userId, boolean force, boolean merge,
                                                  Map<String, ConflictResolution> resolutions) {
        List<DraftChange> unappliedDrafts = getUnappliedDraftsForUser(projectId, userId);

        if (!draftCopyService.isReady(projectId, userId)) {
            DraftCopyStatus status = draftCopyService.getStatus(projectId, userId);
            if (status == DraftCopyStatus.COPYING) {
                return new ApplyDraftsResult(false, 0,
                        "Draft graph copy still in progress — wait before publishing", false, null);
            }
            if (unappliedDrafts.isEmpty()) {
                return new ApplyDraftsResult(true, 0, "No drafts to apply", false, null);
            }
            return new ApplyDraftsResult(false, 0,
                    "Draft session not ready — switch to private mode and wait for the graph copy", false, null);
        }

        if (merge) {
            DraftPublishAnalysis analysis = draftPublishService.analyze(projectId, userId, unappliedDrafts, true);
            if (analysis.isBlocked(force)) {
                String message = analysis.getConflictType() == DraftPublishAnalysis.ConflictType.IRI_OVERLAP
                        ? "Publish blocked: your draft touches entities changed by others since you started editing"
                        : "Publish blocked: the shared ontology changed since your draft started — review, merge, or force publish";
                log.warn("[DRAFT] {} for project {} user {}", message, projectId, userId);
                return new ApplyDraftsResult(false, 0, message, true, analysis);
            }
            try {
                draftPublishMergeService.publishWithThreeWayMerge(projectId, userId, analysis, resolutions);
                mainGraphRevisionService.incrementRevision(projectId);
                draftPublishService.clearBaseline(projectId, userId);
                if (springCacheEviction != null) {
                    springCacheEviction.evictForProject(projectId);
                }
                finalizeAppliedDrafts(projectId, userId, unappliedDrafts);
                return new ApplyDraftsResult(true, unappliedDrafts.size(),
                        "Published draft with merge", false, analysis);
            } catch (Exception e) {
                log.error("[DRAFT] Merge publish failed for project {} user {}", projectId, userId, e);
                return new ApplyDraftsResult(false, 0, "Failed to publish draft: " + e.getMessage(), false, null);
            }
        }

        return applyDraftsViaMoveGraph(projectId, userId, force, false, unappliedDrafts);
    }

    private void finalizeAppliedDrafts(String projectId, String userId, List<DraftChange> unappliedDrafts) {
        if (unappliedDrafts.isEmpty()) {
            return;
        }
        unappliedDrafts.forEach(draft -> collaborativeEditService.broadcastMutation(
                projectId, draftToMutationOp(draft), draft.getUserId(), draft.getUsername()));
        recordDraftsAsChanges(projectId, unappliedDrafts);
        unappliedDrafts.forEach(draft -> draft.setApplied(true));
        draftRepository.saveAll(unappliedDrafts);
        CompletableFuture.runAsync(() -> {
            Map<String, Object> meta = indexService.computeMetadata(projectId);
            meta.put("mainGraphRevision", mainGraphRevisionService.getRevision(projectId));
            metadataService.writeMeta(projectId, meta);
        }, metadataExecutor);
    }
    
    /**
     * Publish a copy-on-switch draft session atomically via SPARQL MOVE GRAPH.
     * Conflict detection: if main has advanced since the copy, block unless force=true.
     */
    private ApplyDraftsResult applyDraftsViaMoveGraph(String projectId, String userId, boolean force, boolean merge,
                                                      List<DraftChange> unappliedDrafts) {
        long mainRevisionAtCopy = draftCopyService.getMainRevisionAtCopy(projectId, userId);
        long currentRevision = mainGraphRevisionService.getRevision(projectId);

        // Block if main changed since copy, unless user explicitly approved (force or merge).
        if (!force && !merge && mainRevisionAtCopy >= 0 && currentRevision > mainRevisionAtCopy) {
            String message = "The shared ontology was updated while you were editing (revision "
                    + mainRevisionAtCopy + " → " + currentRevision + "). "
                    + "Review the changes or use force publish.";
            log.warn("[DRAFT] Conflict blocked for project {} user {}: {}", projectId, userId, message);
            return new ApplyDraftsResult(false, 0, message, true, null);
        }

        try {
            log.info("[DRAFT] Publishing via MOVE GRAPH for project {} user {} (revision {} → {})",
                    projectId, userId, mainRevisionAtCopy, currentRevision);
            datasetService.moveDraftToMain(projectId, userId);
            mainGraphRevisionService.incrementRevision(projectId);
            draftPublishService.clearBaseline(projectId, userId);
            if (springCacheEviction != null) {
                springCacheEviction.evictForProject(projectId);
            }

            if (!unappliedDrafts.isEmpty()) {
                unappliedDrafts.forEach(draft -> collaborativeEditService.broadcastMutation(
                        projectId, draftToMutationOp(draft), draft.getUserId(), draft.getUsername()));
                recordDraftsAsChanges(projectId, unappliedDrafts);
                unappliedDrafts.forEach(draft -> draft.setApplied(true));
                draftRepository.saveAll(unappliedDrafts);
            }

            CompletableFuture.runAsync(() -> {
                Map<String, Object> meta = indexService.computeMetadata(projectId);
                meta.put("mainGraphRevision", mainGraphRevisionService.getRevision(projectId));
                metadataService.writeMeta(projectId, meta);
            }, metadataExecutor);

            log.info("[DRAFT] MOVE GRAPH publish complete for project {} user {} ({} Mongo ops)",
                    projectId, userId, unappliedDrafts.size());
            return new ApplyDraftsResult(true, unappliedDrafts.size(),
                    "Published draft successfully", false, null);
        } catch (Exception e) {
            log.error("[DRAFT] MOVE GRAPH publish failed for project {} user {}", projectId, userId, e);
            return new ApplyDraftsResult(false, 0, "Failed to publish draft: " + e.getMessage(), false, null);
        }
    }

    /**
     * Discard all unapplied drafts for a project
     */
    public DiscardDraftsResult discardDrafts(String projectId, String userId) {
        log.info("[DRAFT] Discarding drafts for project {} user {}", projectId, userId);

        List<DraftChange> unappliedDrafts = userId != null && !userId.isBlank()
                ? getUnappliedDraftsForUser(projectId, userId)
                : getUnappliedDrafts(projectId);
        int count = unappliedDrafts.size();

        if (userId != null && !userId.isBlank()) {
            datasetService.clearDraftGraph(projectId, userId);
            draftPublishService.clearBaseline(projectId, userId);
        } else {
            unappliedDrafts.stream()
                    .map(DraftChange::getUserId)
                    .filter(id -> id != null && !id.isBlank())
                    .distinct()
                    .forEach(id -> {
                        datasetService.clearDraftGraph(projectId, id);
                        draftPublishService.clearBaseline(projectId, id);
                    });
        }

        unappliedDrafts.forEach(draft -> draftRepository.deleteById(draft.getId()));

        log.info("[DRAFT] Discarded {} drafts for project {} user {}", count, projectId, userId);

        return new DiscardDraftsResult(true, count, "Discarded " + count + " draft changes");
    }

    public DiscardDraftsResult discardDrafts(String projectId) {
        return discardDrafts(projectId, null);
    }

    /**
     * Discard unapplied drafts whose operationData.iri is in the given set.
     * Used by pull-from-public resolution when the user chooses "take_public" for specific entities.
     */
    public void discardDraftsByIris(String projectId, String userId, Set<String> iris) {
        List<DraftChange> candidates = userId != null && !userId.isBlank()
                ? getUnappliedDraftsForUser(projectId, userId)
                : getUnappliedDrafts(projectId);
        List<DraftChange> toDelete = candidates.stream()
                .filter(d -> {
                    if (d.getOperationData() == null) return false;
                    Object iriVal = d.getOperationData().get("iri");
                    return iriVal != null && iris.contains(iriVal.toString());
                })
                .collect(Collectors.toList());
        toDelete.forEach(d -> draftRepository.deleteById(d.getId()));
        log.info("[DRAFT] discardDraftsByIris: deleted {} drafts for project {} userId {}", toDelete.size(), projectId, userId);
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
        return getDraftStatistics(projectId, null);
    }

    public Map<String, Object> getDraftStatistics(String projectId, String userId) {
        List<DraftChange> unapplied = userId != null && !userId.isBlank()
            ? getUnappliedDraftsForUser(projectId, userId)
            : getUnappliedDrafts(projectId);
        long unappliedCount = unapplied.size();

        Map<String, Long> operationTypeCounts = unapplied.stream()
            .collect(Collectors.groupingBy(DraftChange::getOperationType, Collectors.counting()));

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalDrafts", unappliedCount);
        stats.put("unappliedDrafts", unappliedCount);
        stats.put("appliedDrafts", 0L);
        stats.put("operationTypeCounts", operationTypeCounts);

        if (!unapplied.isEmpty()) {
            stats.put("oldestDraft", unapplied.get(0).getTimestamp());
            stats.put("newestDraft", unapplied.get(unapplied.size() - 1).getTimestamp());
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
                historyService.recordEdit(
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
            (String) data.get("oldValue"),
            (String) data.get("language"),
            (String) data.get("datatype")
        );
    }
    
    // Result classes
    
    public static class ApplyDraftsResult {
        private final boolean success;
        private final int appliedCount;
        private final String message;
        private final boolean conflictBlocked;
        private final DraftPublishAnalysis publishAnalysis;

        public ApplyDraftsResult(boolean success, int appliedCount, String message,
                                 boolean conflictBlocked, DraftPublishAnalysis publishAnalysis) {
            this.success = success;
            this.appliedCount = appliedCount;
            this.message = message;
            this.conflictBlocked = conflictBlocked;
            this.publishAnalysis = publishAnalysis;
        }

        public boolean isSuccess() { return success; }
        public int getAppliedCount() { return appliedCount; }
        public String getMessage() { return message; }
        public boolean isConflictBlocked() { return conflictBlocked; }
        public DraftPublishAnalysis getPublishAnalysis() { return publishAnalysis; }
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
