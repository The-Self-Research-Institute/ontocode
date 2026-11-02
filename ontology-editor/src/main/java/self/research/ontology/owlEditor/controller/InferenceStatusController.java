package self.research.ontology.owlEditor.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.SparqlQueryContext;
import self.research.ontology.owlEditor.service.SparqlQueryRejectedException;
import self.research.ontology.owlEditor.service.SparqlQueryScope;
import self.research.ontology.owlEditor.service.infer.InferenceService;

import java.util.Map;

@RestController
@RequestMapping("/api/sparql/inference")
@CrossOrigin
public class InferenceStatusController {

    private final SparqlDatasetService datasetService;
    private final InferenceService inferenceService;

    @Autowired
    public InferenceStatusController(SparqlDatasetService datasetService, InferenceService inferenceService) {
        this.datasetService = datasetService;
        this.inferenceService = inferenceService;
    }

    @GetMapping("/{projectId}/status")
    public ResponseEntity<?> status(@PathVariable String projectId) {
        try {
            SparqlQueryScope scope = datasetService.resolveUserQueryScope(projectId);
            String userId = SparqlQueryContext.getUserId();
            if (scope.draft() && (userId == null || userId.isBlank())) {
                throw new SparqlQueryRejectedException("A signed-in user is required to query a draft.");
            }
            return ResponseEntity.ok(inferenceService.status(scope, userId));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }
}
