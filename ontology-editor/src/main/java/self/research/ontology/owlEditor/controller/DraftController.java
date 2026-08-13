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
import self.research.ontology.owlEditor.service.DraftPublishMergeService;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final DraftPublishMergeService draftPublishMergeService;

    public DraftController(DraftTrackingService draftTrackingService,
                           DraftCopyService draftCopyService,
                           ProjectMetadataService metadataService,
                           DraftPullRequestRepository prRepository,
                           WorkspaceOwnershipService ownershipService,
                           DraftPublishMergeService draftPublishMergeService) {
        this.draftTrackingService = draftTrackingService;
        this.draftCopyService = draftCopyService;
        this.metadataService = metadataService;
        this.prRepository = prRepository;
        this.ownershipService = ownershipService;
        this.draftPublishMergeService = draftPublishMergeService;
    }

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

    @PostMapping("/{projectId}/pull-from-public/analyze")
    public ResponseEntity<Map<String, Object>> analyzePublicDiff(
            @PathVariable String projectId,
            @RequestParam String userId) {
        try {
            Map<String, Object> analysis = draftPublishMergeService.analyzePull(projectId, userId);
            Map<String, Object> response = new HashMap<>(analysis);
            response.put("success", true);
            response.put("projectId", projectId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[PULL-ANALYZE] Error analysing draft vs public for project {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/{projectId}/pull-from-public/apply")
    public ResponseEntity<Map<String, Object>> applyPublicPull(
            @PathVariable String projectId,
            @RequestParam String userId,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> resolutionChoices = body != null
                    ? (Map<String, String>) body.getOrDefault("resolutions", Map.of())
                    : Map.of();

            Map<String, ConflictResolution> resolutions = new HashMap<>();
            for (Map.Entry<String, String> entry : resolutionChoices.entrySet()) {

                ResolutionAction action = switch (entry.getValue()) {
                    case "keep_draft" -> ResolutionAction.KEEP_TARGET;
                    case "take_public" -> ResolutionAction.KEEP_SOURCE;
                    case "merge" -> ResolutionAction.MERGE;
                    case "keep_both" -> ResolutionAction.RENAME_SOURCE;
                    case "skip" -> ResolutionAction.SKIP;
                    default -> null;
                };
                if (action != null) {
                    ConflictResolution cr = new ConflictResolution();
                    cr.setAction(action);
                    resolutions.put(entry.getKey(), cr);
                }
            }

            Map<String, Object> result = draftPublishMergeService.applyPull(projectId, userId, resolutions);
            Map<String, Object> response = new HashMap<>(result);
            response.put("projectId", projectId);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[PULL-APPLY] Error applying pull for project {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

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
