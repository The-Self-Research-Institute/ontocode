package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.GraphDBHistoryService;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin
public class OntologyCrudController {

    private static final Logger log = LoggerFactory.getLogger(OntologyCrudController.class);

    private final OntologyMutationService mutationService;
    private final DraftTrackingService draftTrackingService;
    private final GraphDBHistoryService historyService;
    private final CollaborativeEditService collaborativeEditService;

    public OntologyCrudController(OntologyMutationService mutationService,
                                 DraftTrackingService draftTrackingService,
                                 GraphDBHistoryService historyService,
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
            // Record as draft - don't apply to GraphDB yet
            log.info("[MUTATION] Recording {} operations as draft for project {}", 
                request.ops().size(), projectId);
            
            String userId = request.userId() != null ? request.userId() : "anonymous";
            String username = request.username() != null ? request.username() : "Anonymous";
            String sessionId = request.sessionId() != null ? request.sessionId() : 
                UUID.randomUUID().toString();
            
            draftTrackingService.recordDrafts(projectId, userId, username, request.ops(), sessionId);
            
            return ResponseEntity.ok(Map.of(
                "success", true, 
                "draft", true,
                "message", "Changes recorded as draft"
            ));
        } else {
            try {
                // Apply directly to GraphDB and record to history
                log.info("[MUTATION] Applying {} operations directly to GraphDB for project {}",
                    request.ops().size(), projectId);

                String userId = request.userId() != null ? request.userId() : "anonymous";
                String username = request.username() != null ? request.username() : "Anonymous";

                mutationService.apply(projectId, request.ops());

                // Record each operation to GraphDB history and broadcast to collaborators
                for (OntologyMutationService.MutationOp op : request.ops()) {
                    String entityIRI = op.iri();
                    String entityLabel = op.label();
                    String oldValue = op.oldValue();
                    String newValue = op.value();
                    String annotationProperty = op.property();

                    historyService.recordEdit(
                        projectId,
                        userId,
                        username,
                        op.type(),
                        entityIRI,
                        entityLabel,
                        oldValue,
                        newValue,
                        op.type() + " operation",
                        annotationProperty
                    );

                    collaborativeEditService.broadcastMutation(projectId, op, userId, username);
                }

                log.info("[MUTATION] Recorded {} changes to GraphDB history", request.ops().size());

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
