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
import self.research.ontology.owlEditor.service.OntologyMutationService;

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

    public OntologyCrudController(OntologyMutationService mutationService,
                                 DraftTrackingService draftTrackingService) {
        this.mutationService = mutationService;
        this.draftTrackingService = draftTrackingService;
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
            // Apply directly to GraphDB (legacy behavior)
            log.info("[MUTATION] Applying {} operations directly to GraphDB for project {}", 
                request.ops().size(), projectId);
            mutationService.apply(projectId, request.ops());
            return ResponseEntity.ok(Map.of(
                "success", true,
                "draft", false,
                "message", "Changes applied directly"
            ));
        }
    }

    @PostMapping("/make-siblings-disjoint/{projectId}")
    public ResponseEntity<?> makeSiblingsDisjoint(@PathVariable String projectId,
                                                  @RequestBody MakeSiblingsDisjointRequest request) {
        mutationService.makeSiblingsDisjoint(projectId, request.classIds());
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
