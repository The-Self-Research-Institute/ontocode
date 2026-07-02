package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.model.DraftChange;
import self.research.ontology.owlEditor.model.DraftCopyStatus;
import self.research.ontology.owlEditor.model.DraftPullRequest;
import self.research.ontology.owlEditor.model.merge.ConflictResolution;
import self.research.ontology.owlEditor.model.merge.ResolutionAction;
import self.research.ontology.owlEditor.repository.DraftPullRequestRepository;
import self.research.ontology.owlEditor.service.DraftCopyService;
import self.research.ontology.owlEditor.service.DraftPublishAnalysis;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final DraftPullRequestRepository prRepository;
    private final WorkspaceOwnershipService ownershipService;

    public DraftController(DraftTrackingService draftTrackingService,
                           DraftCopyService draftCopyService,
                           ProjectMetadataService metadataService,
                           DraftPullRequestRepository prRepository,
                           WorkspaceOwnershipService ownershipService) {
        this.draftTrackingService = draftTrackingService;
        this.draftCopyService = draftCopyService;
        this.metadataService = metadataService;
        this.prRepository = prRepository;
        this.ownershipService = ownershipService;
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
    public ResponseEntity<Map<String, Object>> getDrafts(
            @PathVariable String projectId,
            @RequestParam(required = false) String userId) {
        try {
            List<DraftChange> drafts = (userId != null && !userId.isBlank())
                    ? draftTrackingService.getUnappliedDraftsForUser(projectId, userId)
                    : draftTrackingService.getUnappliedDrafts(projectId);

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

    // -----------------------------------------------------------------------
    // Pull-from-public: analyse differences and apply resolution
    // -----------------------------------------------------------------------

    /**
     * Analyse differences between a user's draft and the current public state.
     * POST /api/ontology/{projectId}/pull-from-public/analyze
     */
    @PostMapping("/{projectId}/pull-from-public/analyze")
    public ResponseEntity<Map<String, Object>> analyzePublicDiff(
            @PathVariable String projectId,
            @RequestParam(required = false) String userId) {
        try {
            List<DraftChange> drafts = userId != null && !userId.isBlank()
                    ? draftTrackingService.getUnappliedDraftsForUser(projectId, userId)
                    : draftTrackingService.getUnappliedDrafts(projectId);

            java.util.Map<String, List<DraftChange>> byIri = new java.util.LinkedHashMap<>();
            for (DraftChange d : drafts) {
                String iri = d.getOperationData() != null ? (String) d.getOperationData().get("iri") : null;
                if (iri == null) iri = d.getId();
                byIri.computeIfAbsent(iri, k -> new java.util.ArrayList<>()).add(d);
            }

            List<Map<String, Object>> conflicts = new java.util.ArrayList<>();
            List<Map<String, Object>> safeChanges = new java.util.ArrayList<>();

            for (Map.Entry<String, List<DraftChange>> entry : byIri.entrySet()) {
                List<DraftChange> group = entry.getValue();
                DraftChange last = group.get(group.size() - 1);
                String label = last.getOperationData() != null
                        ? (String) last.getOperationData().getOrDefault("label", entry.getKey())
                        : entry.getKey();
                String opType = last.getOperationType() != null ? last.getOperationType() : "MODIFY";
                String description = last.getOperationData() != null
                        ? (String) last.getOperationData().getOrDefault("description", opType + " on " + label)
                        : opType + " on " + label;

                if (group.size() > 1) {
                    Map<String, Object> conflict = new HashMap<>();
                    conflict.put("entityIri", entry.getKey());
                    conflict.put("entityLabel", label);
                    conflict.put("changeType", opType);
                    conflict.put("draftDescription", description);
                    conflict.put("publicDescription", "Current public version of " + label);
                    conflict.put("resolution", null);
                    conflicts.add(conflict);
                } else {
                    Map<String, Object> safe = new HashMap<>();
                    safe.put("entityIri", entry.getKey());
                    safe.put("entityLabel", label);
                    safe.put("changeType", opType);
                    safe.put("description", description);
                    safeChanges.add(safe);
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("hasConflicts", !conflicts.isEmpty());
            result.put("conflicts", conflicts);
            result.put("safeChanges", safeChanges);
            result.put("draftChanges", safeChanges);
            result.put("draftCount", drafts.size());
            result.put("publicVersion", "current");
            result.put("projectId", projectId);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("[PULL-ANALYZE] Error analysing draft vs public for project {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Apply the user's resolution choices — auto-merge, per-entity choice, or full overwrite.
     * POST /api/ontology/{projectId}/pull-from-public/apply
     * Body: { "strategy": "auto"|"resolved"|"overwrite", "conflictResolutions": { "<iri>": "keep_draft"|"take_public" } }
     */
    @PostMapping("/{projectId}/pull-from-public/apply")
    public ResponseEntity<Map<String, Object>> applyPublicPull(
            @PathVariable String projectId,
            @RequestParam(required = false) String userId,
            @RequestBody Map<String, Object> body) {
        try {
            String strategy = (String) body.getOrDefault("strategy", "auto");
            @SuppressWarnings("unchecked")
            Map<String, String> resolutions = (Map<String, String>) body.getOrDefault("conflictResolutions", Map.of());

            List<DraftChange> drafts = userId != null && !userId.isBlank()
                    ? draftTrackingService.getUnappliedDraftsForUser(projectId, userId)
                    : draftTrackingService.getUnappliedDrafts(projectId);

            if ("overwrite".equals(strategy)) {
                draftTrackingService.discardDrafts(projectId, userId);
                log.info("[PULL-APPLY] Overwrite: discarded {} drafts for project {}", drafts.size(), projectId);
            } else if ("resolved".equals(strategy)) {
                Set<String> toDiscard = new java.util.HashSet<>();
                for (Map.Entry<String, String> r : resolutions.entrySet()) {
                    if ("take_public".equals(r.getValue())) toDiscard.add(r.getKey());
                }
                if (!toDiscard.isEmpty()) {
                    draftTrackingService.discardDraftsByIris(projectId, userId, toDiscard);
                    log.info("[PULL-APPLY] Resolved: discarded drafts for {} IRIs in project {}", toDiscard.size(), projectId);
                }
            } else {
                log.info("[PULL-APPLY] Auto-merge: no conflicts for project {}", projectId);
            }

            return ResponseEntity.ok(Map.of("success", true, "strategy", strategy, "projectId", projectId));

        } catch (Exception e) {
            log.error("[PULL-APPLY] Error applying pull for project {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // ── Draft Pull Requests ───────────────────────────────────────────────────

    /**
     * Raise a PR from the caller's current draft changes.
     * POST /api/ontology/{projectId}/draft-prs
     * Body: { "userId": "...", "username": "...", "title": "...", "description": "..." }
     */
    @PostMapping("/{projectId}/draft-prs")
    public ResponseEntity<Map<String, Object>> raisePullRequest(
            @PathVariable String projectId,
            @RequestBody RaisePRRequest request) {
        try {
            String userId = request.userId();
            if (userId == null || userId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "userId is required"));
            }

            if (prRepository.findByProjectIdAndAuthorIdAndStatus(projectId, userId, DraftPullRequest.Status.OPEN).isPresent()) {
                return ResponseEntity.status(409).body(Map.of(
                        "success", false,
                        "error", "You already have an open pull request for this project. Close or wait for it to be reviewed before raising another."
                ));
            }

            int changeCount = draftTrackingService.getUnappliedDraftsForUser(projectId, userId).size();
            if (changeCount == 0) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "No draft changes found. Make some changes in Draft Mode before raising a pull request."
                ));
            }

            DraftPullRequest pr = new DraftPullRequest();
            pr.setProjectId(projectId);
            pr.setAuthorId(userId);
            pr.setAuthorUsername(request.username() != null ? request.username() : userId);
            pr.setTitle(request.title() != null && !request.title().isBlank() ? request.title() : "Draft changes by " + pr.getAuthorUsername());
            pr.setDescription(request.description());
            pr.setChangeCount(changeCount);

            DraftPullRequest saved = prRepository.save(pr);
            log.info("[DRAFT PR] PR raised prId={} project={} author={} changes={}", saved.getId(), projectId, userId, changeCount);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "prId", saved.getId(),
                    "changeCount", changeCount,
                    "message", "Pull request raised successfully"
            ));
        } catch (Exception e) {
            log.error("[DRAFT PR] Error raising PR for project {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * List PRs for a project (all statuses). Optionally filter by status.
     * GET /api/ontology/{projectId}/draft-prs?status=OPEN
     */
    @GetMapping("/{projectId}/draft-prs")
    public ResponseEntity<Map<String, Object>> listPullRequests(
            @PathVariable String projectId,
            @RequestParam(required = false) String status) {
        try {
            List<DraftPullRequest> prs;
            if (status != null && !status.isBlank()) {
                try {
                    prs = prRepository.findByProjectIdAndStatusOrderByCreatedAtDesc(
                            projectId, DraftPullRequest.Status.valueOf(status.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Invalid status: " + status));
                }
            } else {
                prs = prRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
            }
            long openCount = prRepository.countByProjectIdAndStatus(projectId, DraftPullRequest.Status.OPEN);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "projectId", projectId,
                    "prs", prs,
                    "openCount", openCount
            ));
        } catch (Exception e) {
            log.error("[DRAFT PR] Error listing PRs for project {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * Approve a PR — merges the author's draft into the public ontology.
     * POST /api/ontology/{projectId}/draft-prs/{prId}/approve
     */
    @PostMapping("/{projectId}/draft-prs/{prId}/approve")
    public ResponseEntity<Map<String, Object>> approvePullRequest(
            @PathVariable String projectId,
            @PathVariable String prId,
            @RequestBody ReviewPRRequest request) {
        try {
            String reviewerId = request.reviewerId();
            if (!ownershipService.canPublishToProject(reviewerId, projectId)) {
                return ResponseEntity.status(403).body(Map.of(
                        "success", false,
                        "error", "Only project owners, admins, or editors can approve pull requests"
                ));
            }

            DraftPullRequest pr = prRepository.findById(prId).orElse(null);
            if (pr == null || !pr.getProjectId().equals(projectId)) {
                return ResponseEntity.status(404).body(Map.of("success", false, "error", "Pull request not found"));
            }
            if (pr.getStatus() != DraftPullRequest.Status.OPEN) {
                return ResponseEntity.status(409).body(Map.of("success", false, "error", "Pull request is already " + pr.getStatus().name().toLowerCase()));
            }

            DraftTrackingService.ApplyDraftsResult result =
                    draftTrackingService.applyDrafts(projectId, pr.getAuthorId(), true, false, null);

            if (!result.isSuccess() && result.isConflictBlocked()) {
                return ResponseEntity.status(409).body(Map.of(
                        "success", false,
                        "error", "Merge conflicts detected. Resolve conflicts before approving.",
                        "conflictBlocked", true
                ));
            }

            pr.setStatus(DraftPullRequest.Status.APPROVED);
            pr.setReviewedAt(java.time.Instant.now());
            pr.setReviewerId(reviewerId);
            pr.setReviewNote(request.reviewNote());
            prRepository.save(pr);

            log.info("[DRAFT PR] PR approved prId={} project={} author={} reviewer={} applied={}",
                    prId, projectId, pr.getAuthorId(), reviewerId, result.getAppliedCount());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "appliedCount", result.getAppliedCount(),
                    "message", "Pull request approved and " + result.getAppliedCount() + " changes merged"
            ));
        } catch (Exception e) {
            log.error("[DRAFT PR] Error approving PR {} for project {}", prId, projectId, e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * Reject a PR — leaves the author's draft intact (they can revise and re-raise).
     * POST /api/ontology/{projectId}/draft-prs/{prId}/reject
     */
    @PostMapping("/{projectId}/draft-prs/{prId}/reject")
    public ResponseEntity<Map<String, Object>> rejectPullRequest(
            @PathVariable String projectId,
            @PathVariable String prId,
            @RequestBody ReviewPRRequest request) {
        try {
            String reviewerId = request.reviewerId();
            if (!ownershipService.canPublishToProject(reviewerId, projectId)) {
                return ResponseEntity.status(403).body(Map.of(
                        "success", false,
                        "error", "Only project owners, admins, or editors can reject pull requests"
                ));
            }

            DraftPullRequest pr = prRepository.findById(prId).orElse(null);
            if (pr == null || !pr.getProjectId().equals(projectId)) {
                return ResponseEntity.status(404).body(Map.of("success", false, "error", "Pull request not found"));
            }
            if (pr.getStatus() != DraftPullRequest.Status.OPEN) {
                return ResponseEntity.status(409).body(Map.of("success", false, "error", "Pull request is already " + pr.getStatus().name().toLowerCase()));
            }

            pr.setStatus(DraftPullRequest.Status.REJECTED);
            pr.setReviewedAt(java.time.Instant.now());
            pr.setReviewerId(reviewerId);
            pr.setReviewNote(request.reviewNote());
            prRepository.save(pr);

            log.info("[DRAFT PR] PR rejected prId={} project={} author={} reviewer={}", prId, projectId, pr.getAuthorId(), reviewerId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Pull request rejected. The author's draft changes are preserved."
            ));
        } catch (Exception e) {
            log.error("[DRAFT PR] Error rejecting PR {} for project {}", prId, projectId, e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
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

    public record RaisePRRequest(String userId, String username, String title, String description) {}

    public record ReviewPRRequest(String reviewerId, String reviewNote) {}
}
