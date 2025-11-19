package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.OntologyMutationService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin
public class OntologyCrudController {

    private final OntologyMutationService mutationService;

    public OntologyCrudController(OntologyMutationService mutationService) {
        this.mutationService = mutationService;
    }

    @PostMapping("/mutations/{projectId}")
    public ResponseEntity<?> mutate(@PathVariable String projectId,
                                    @RequestBody MutationRequest request) {
        mutationService.apply(projectId, request.ops());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/make-siblings-disjoint/{projectId}")
    public ResponseEntity<?> makeSiblingsDisjoint(@PathVariable String projectId,
                                                  @RequestBody MakeSiblingsDisjointRequest request) {
        mutationService.makeSiblingsDisjoint(projectId, request.classIds());
        return ResponseEntity.ok(Map.of("success", true));
    }

    public record MutationRequest(List<OntologyMutationService.MutationOp> ops) {}
    public record MakeSiblingsDisjointRequest(List<String> classIds) {}
}
