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
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.OntologyHistoryService;
import self.research.ontology.owlEditor.service.OntologyMutationService;
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
    private final OntologyHistoryService historyService;
    private final CollaborativeEditService collaborativeEditService;

    @Value("${ontocode.desktop.mode:false}")
    private boolean desktopMode;

    private static final String DESKTOP_USER_ID = "desktop-user-local";

    public OntologyCrudController(OntologyMutationService mutationService,
                                 DraftTrackingService draftTrackingService,
                                 OntologyHistoryService historyService,
                                 CollaborativeEditService collaborativeEditService) {
        this.mutationService = mutationService;
        this.draftTrackingService = draftTrackingService;
        this.historyService = historyService;
        this.collaborativeEditService = collaborativeEditService;
    }

    @PostMapping("/mutations/{projectId}")
    public ResponseEntity<?> mutate(@PathVariable String projectId,
                                    @RequestBody MutationRequest request,
                                    @RequestParam(required = false, defaultValue = "true") boolean draft) {
        
        if (draft) {
            // Private mode: persist to per-user draft named graph + MongoDB audit trail
            log.info("[MUTATION] Applying {} operations to draft graph for project {}",
                request.ops().size(), projectId);

            String userId = request.userId() != null ? request.userId() : "anonymous";
            if (desktopMode) {
                userId = DESKTOP_USER_ID;
            }
            String username = request.username() != null ? request.username() : "Anonymous";
            String sessionId = request.sessionId() != null ? request.sessionId() :
                UUID.randomUUID().toString();

            mutationService.applyDraft(projectId, userId, request.ops());
            draftTrackingService.recordDrafts(projectId, userId, username, request.ops(), sessionId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "draft", true,
                "message", "Changes saved to your private draft"
            ));
        } else {
            try {
                // Apply directly to GraphDB and record to history
                log.info("[MUTATION] Applying {} operations directly to GraphDB for project {}",
                    request.ops().size(), projectId);

                String userId = request.userId() != null ? request.userId() : "anonymous";
                if (desktopMode) {
                    userId = DESKTOP_USER_ID;
                }
                String username = request.username() != null ? request.username() : "Anonymous";

                mutationService.apply(projectId, request.ops());

                // Record history and broadcast asynchronously — mutation is already applied and
                // committed; history/broadcast are best-effort and must not block the response.
                final String finalUserId = userId;
                final String finalUsername = username;
                final List<OntologyMutationService.MutationOp> ops = request.ops();
                CompletableFuture.runAsync(() -> {
                    for (OntologyMutationService.MutationOp op : ops) {
                        try {
                            historyService.recordEdit(
                                projectId, finalUserId, finalUsername,
                                op.type(), op.iri(), op.label(),
                                op.oldValue(), op.value(),
                                op.type() + " operation", op.property()
                            );
                            collaborativeEditService.broadcastMutation(projectId, op, finalUserId, finalUsername);
                        } catch (Exception e) {
                            log.warn("[MUTATION] Async history/broadcast failed for op {}: {}", op.type(), e.getMessage());
                        }
                    }
                    log.info("[MUTATION] Recorded {} changes to GraphDB history (async)", ops.size());
                }, historyExecutor);

                return ResponseEntity.ok(Map.of(
                    "success", true,
                    "draft", false,
                    "message", "Changes applied directly"
                ));
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
    
    @PostMapping("/make-siblings-disjoint/{projectId}")
    public ResponseEntity<?> makeSiblingsDisjoint(@PathVariable String projectId,
                                                  @RequestBody MakeSiblingsDisjointRequest request,
                                                  @RequestParam(required = false, defaultValue = "anonymous") String userId,
                                                  @RequestParam(required = false, defaultValue = "Anonymous") String username) {
        mutationService.makeSiblingsDisjoint(projectId, request.classIds());
        
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
                    null  // datatype
                );
                collaborativeEditService.broadcastMutation(projectId, disjointOp, userId, username);
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
