package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.DraftCopyStatus;
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
    private final DraftChangeHistoryRecorder draftChangeHistory;
    private final Executor metadataExecutor;
    private final CollaborativeEditService collaborativeEditService;
    private final DraftPublishService draftPublishService;
    private final MainGraphRevisionService mainGraphRevisionService;
    private final DraftPublishMergeService draftPublishMergeService;
    private final DraftCopyService draftCopyService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OntologySpringCacheEvictionService springCacheEviction;

    // Invalidated when drafts are published so the class tree reflects the new public graph.
    // Publish runs in the publishing user's SPARQL context, so execUpdate's derived-cache
    // choke point takes its draft branch and leaves public caches alone — the explicit
    // project-wide invalidation must happen here.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private HierarchyIndexService hierarchyIndexService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TopLevelClassCacheService topLevelClassCacheService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ClassDetailCacheService classDetailCacheService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EntityUsageIndexService entityUsageIndexService;

    /** Drop hierarchy caches after the public graph changes so the served tree is not stale. */
    private void invalidateHierarchyCaches(String projectId) {
        // Must happen before markStale()'s async rebuild runs, or the rebuild trusts the
        // stale on-disk ontology file instead of re-exporting from Fuseki — see
        // OntologyMutationService.markDirtyAfterRawWrite() for the same ordering requirement
        // on the single-mutation path.
        datasetService.markProjectDirty(projectId);
        if (topLevelClassCacheService != null) topLevelClassCacheService.evict(projectId);
        if (hierarchyIndexService != null) hierarchyIndexService.markStale(projectId);
        if (classDetailCacheService != null) classDetailCacheService.dropAll(projectId);
        if (entityUsageIndexService != null) {
            entityUsageIndexService.dropAll(projectId);
            entityUsageIndexService.scheduleBuild(projectId);
        }
        // A PR merge writes straight to the public graph without going through
        // OntologyMutationService.apply()/execUpdate()'s normal cache-invalidation choke
        // point, so the Code View cache and reasoner caches need an explicit bust here too.
        mutationService.invalidatePublicCodeViewCache(projectId, false);
        mutationService.invalidateReasonerCaches(projectId);
    }

    public DraftTrackingService(DraftChangeRepository draftRepository,
                               OntologyMutationService mutationService,
                               SparqlDatasetService datasetService,
                               OntologyIndexService indexService,
                               ProjectMetadataService metadataService,
                               DraftChangeHistoryRecorder draftChangeHistory,
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
        this.draftChangeHistory = draftChangeHistory;
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
                invalidateHierarchyCaches(projectId);
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
        draftChangeHistory.markPublished(projectId, userId);
        if (unappliedDrafts.isEmpty()) {
            return;
        }
        unappliedDrafts.forEach(draft -> collaborativeEditService.broadcastMutation(
                projectId, draftToMutationOp(draft), draft.getUserId(), draft.getUsername()));
        draftChangeHistory.record(projectId, unappliedDrafts);
        unappliedDrafts.forEach(draft -> draft.setApplied(true));
        draftRepository.saveAll(unappliedDrafts);
        CompletableFuture.runAsync(() -> {
            Map<String, Object> meta = indexService.computeMetadata(projectId);
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
            invalidateHierarchyCaches(projectId);
            draftChangeHistory.markPublished(projectId, userId);

            if (!unappliedDrafts.isEmpty()) {
                unappliedDrafts.forEach(draft -> collaborativeEditService.broadcastMutation(
                        projectId, draftToMutationOp(draft), draft.getUserId(), draft.getUsername()));
                draftChangeHistory.record(projectId, unappliedDrafts);
                unappliedDrafts.forEach(draft -> draft.setApplied(true));
                draftRepository.saveAll(unappliedDrafts);
            }

            CompletableFuture.runAsync(() -> {
                Map<String, Object> meta = indexService.computeMetadata(projectId);
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

    private static final int ORPHANED_DRAFT_RETENTION_DAYS = 30;

    @org.springframework.scheduling.annotation.Scheduled(cron = "0 30 3 * * *")
    public void cleanupOrphanedUnappliedDrafts() {
        LocalDateTime cutoff = LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(ORPHANED_DRAFT_RETENTION_DAYS);
        long deleted = draftRepository.deleteByAppliedFalseAndTimestampBefore(cutoff);
        if (deleted > 0) {
            log.info("[DRAFT] Cleaned up {} unapplied draft record(s) older than {} days", deleted, ORPHANED_DRAFT_RETENTION_DAYS);
        }
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
            (String) data.get("datatype"),
            (String) data.get("ancestorIri")
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
