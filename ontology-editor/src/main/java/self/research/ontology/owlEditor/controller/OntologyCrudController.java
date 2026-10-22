package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.DraftNotReadyException;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.ExactChangeRecorder;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.RequestActor;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin
public class OntologyCrudController {

    private static final Logger log = LoggerFactory.getLogger(OntologyCrudController.class);

    // Dedicated pool for async history recording + collaboration broadcast.
    // Isolated from the ForkJoinPool.commonPool used by SPARQL query parallelism.
    // 4 threads: enough for burst concurrent mutations without unbounded growth.
    private static final ExecutorService historyExecutor = Executors.newFixedThreadPool(4);

    private final OntologyMutationService mutationService;
    private final DraftTrackingService draftTrackingService;
    private final CollaborativeEditService collaborativeEditService;
    private final ProjectMetadataService metadataService;
    private final ProjectWriteLockRegistry lockRegistry;
    private final ExactChangeRecorder exactRecorder;

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    private static final String DESKTOP_USER_ID = "desktop-user-local";

    public OntologyCrudController(OntologyMutationService mutationService,
                                 DraftTrackingService draftTrackingService,
                                 CollaborativeEditService collaborativeEditService,
                                 ProjectMetadataService metadataService,
                                 ProjectWriteLockRegistry lockRegistry,
                                 ExactChangeRecorder exactRecorder) {
        this.mutationService = mutationService;
        this.draftTrackingService = draftTrackingService;
        this.collaborativeEditService = collaborativeEditService;
        this.metadataService = metadataService;
        this.lockRegistry = lockRegistry;
        this.exactRecorder = exactRecorder;
    }

    @PostMapping("/mutations/{projectId}")
    public ResponseEntity<?> mutate(@PathVariable String projectId,
                                    @RequestBody MutationRequest request,
                                    @RequestParam(required = false, defaultValue = "true") boolean draft,
                                    jakarta.servlet.http.HttpServletRequest httpRequest) {

        java.util.Optional<String> badIri = self.research.ontology.owlEditor.service.MutationIriGuard.firstProblem(request.ops());
        if (badIri.isPresent()) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "draft", draft,
                "error", "Bad Request",
                "message", badIri.get()
            ));
        }

        // Enforce requireDraftForMembers: silently redirect public mutations to draft if the
        // project owner has turned on draft-only mode and this user is not the owner.
        if (!draft && !desktopMode) {
            String userId = RequestActor.of(httpRequest, request.userId(), request.username()).userId();
            if (metadataService.isRequireDraftForMembers(projectId)) {
                String ownerEmail = metadataService.getOwnerEmail(projectId).orElse(null);
                if (!userId.equals(ownerEmail)) {
                    log.info("[MUTATION] requireDraftForMembers is on — redirecting public mutation to draft for user {} on project {}",
                            userId, projectId);
                    draft = true;
                }
            }
        }

        if (draft) {
            log.info("[MUTATION] Applying {} operations to draft graph for project {}",
                request.ops().size(), projectId);

            String userId = RequestActor.forScope(httpRequest, request.userId(), true);
            if (userId == null) {
                userId = "anonymous";
            }
            if (desktopMode) {
                userId = DESKTOP_USER_ID;
            }
            String username = RequestActor.displayName(httpRequest, request.username());
            String sessionId = request.sessionId() != null ? request.sessionId() :
                UUID.randomUUID().toString();

            String draftChangeSetId;
            try {
                final String draftUserId = userId;
                final String draftUsername = username;
                final String draftSessionId = sessionId;
                draftChangeSetId = lockRegistry.runExclusive(projectId, () -> exactRecorder.recordGrouped(projectId, true,
                        draftUserId, draftUsername, request.ops(), () -> {
                            mutationService.applyDraft(projectId, draftUserId, request.ops());
                            draftTrackingService.recordDrafts(projectId, draftUserId, draftUsername, request.ops(), draftSessionId);
                        }));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.status(409).body(Map.of(
                    "success", false,
                    "draft", true,
                    "error", e.getMessage(),
                    "draftCopyNotReady", true
                ));
            } catch (DraftNotReadyException e) {
                return ResponseEntity.status(409).body(Map.of(
                    "success", false,
                    "draft", true,
                    "error", e.getMessage(),
                    "draftCopyNotReady", true
                ));
            } catch (Exception e) {
                log.error("[MUTATION] Failed to apply draft mutations for project {}: {}", projectId, e.getMessage(), e);
                return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "draft", true,
                    "error", "Internal Server Error",
                    "message", "Failed to apply mutations"
                ));
            }

            return ResponseEntity.ok(ExactChangeRecorder.appliedBody(true, "Changes saved to your private draft", draftChangeSetId));
        } else {
            try {
                // Apply directly to GraphDB and record to history
                log.info("[MUTATION] Applying {} operations directly to GraphDB for project {}",
                    request.ops().size(), projectId);

                RequestActor actor = RequestActor.of(httpRequest, request.userId(), request.username());
                String userId = actor.userId();
                if (desktopMode) {
                    userId = DESKTOP_USER_ID;
                }
                String username = request.username() != null ? request.username() : actor.username();

                final String finalUserId = userId;
                final String finalUsername = username;
                final List<OntologyMutationService.MutationOp> ops = request.ops();
                String changeSetId = lockRegistry.runExclusive(projectId, () -> exactRecorder.recordGrouped(projectId, false,
                        finalUserId, finalUsername, ops, () -> mutationService.apply(projectId, ops)));

                CompletableFuture.runAsync(() -> {
                    for (OntologyMutationService.MutationOp op : ops) {
                        try {
                            collaborativeEditService.broadcastMutation(projectId, op, finalUserId, finalUsername);
                        } catch (Exception e) {
                            log.warn("[MUTATION] Async broadcast failed for op {}: {}", op.type(), e.getMessage());
                        }
                    }
                }, historyExecutor);

                return ResponseEntity.ok(ExactChangeRecorder.appliedBody(false, "Changes applied directly", changeSetId));
            } catch (IllegalArgumentException e) {
                log.warn("[MUTATION] Rejected mutation request for project {}: {}", projectId, e.getMessage());
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "draft", false,
                    "error", "Bad Request",
                    "message", e.getMessage()
                ));
            } catch (Exception e) {
                log.error("[MUTATION] Failed to apply mutations for project {}: {}", projectId, e.getMessage(), e);
                return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "draft", false,
                    "error", "Internal Server Error",
                    "message", "Failed to apply mutations"
                ));
            }
        }
    }
    
    private static List<OntologyMutationService.MutationOp> pairwiseDisjointOps(List<String> classIds) {
        List<OntologyMutationService.MutationOp> ops = new java.util.ArrayList<>();
        for (int i = 0; classIds != null && i < classIds.size(); i++) {
            for (int j = i + 1; j < classIds.size(); j++) {
                ops.add(new OntologyMutationService.MutationOp("addDisjointWith", classIds.get(i), null, null, null, null,
                        classIds.get(j), null, null, null, null, null, null, null, null));
            }
        }
        return ops;
    }

    @PostMapping("/make-siblings-disjoint/{projectId}")
    public ResponseEntity<?> makeSiblingsDisjoint(@PathVariable String projectId,
                                                  @RequestBody MakeSiblingsDisjointRequest request,
                                                  @RequestParam(required = false, defaultValue = "anonymous") String userId,
                                                  @RequestParam(required = false, defaultValue = "Anonymous") String username,
                                                  @RequestParam(required = false, defaultValue = "false") boolean draft,
                                                  jakarta.servlet.http.HttpServletRequest httpRequest) {
        try {
            final String actorId = RequestActor.forScope(httpRequest, userId, draft);
            final String actorName = "Anonymous".equals(username) ? actorId : username;
            lockRegistry.runExclusive(projectId, () -> exactRecorder.recordGrouped(projectId, draft, actorId, actorName,
                    pairwiseDisjointOps(request.classIds()),
                    () -> mutationService.makeSiblingsDisjoint(projectId, request.classIds(), draft, actorId)));
        } catch (Exception e) {
            log.error("[MUTATION] Failed to make siblings disjoint for project {}: {}", projectId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("success", false, "message", "Failed to make siblings disjoint"));
        }

        // Draft edits are private — never broadcast them to other collaborators.
        if (draft) {
            return ResponseEntity.ok(Map.of("success", true, "draft", true));
        }

        // Broadcast disjoint axiom changes to collaborators
        for (int i = 0; i < request.classIds().size(); i++) {
            for (int j = i + 1; j < request.classIds().size(); j++) {
                OntologyMutationService.MutationOp disjointOp = new OntologyMutationService.MutationOp(
                    "addDisjointWith",
                    request.classIds().get(i),
                    null,
                    null,
                    null,
                    null,
                    request.classIds().get(j),
                    null,
                    null, // restrictionType
                    null, // cardinality
                    null, // axiomType
                    null, // oldValue
                    null, // language
                    null, // datatype
                    null  // ancestorIri
                );
                String broadcastId = RequestActor.forScope(httpRequest, userId, draft);
                collaborativeEditService.broadcastMutation(projectId, disjointOp, broadcastId, "Anonymous".equals(username) ? broadcastId : username);
            }
        }
        
        return ResponseEntity.ok(Map.of("success", true));
    }

    public record MutationRequest(
        List<OntologyMutationService.MutationOp> ops,
        String userId,
        String username,
        String sessionId
    ) {}
    
    public record MakeSiblingsDisjointRequest(List<String> classIds) {}
}
