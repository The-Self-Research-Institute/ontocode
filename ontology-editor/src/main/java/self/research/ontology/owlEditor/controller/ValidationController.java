package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import self.research.ontology.owlEditor.service.OntologyValidationService;

import java.util.Map;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin(origins = "*")
public class ValidationController {

    private final OntologyValidationService validator;

    public ValidationController(OntologyValidationService validator) {
        this.validator = validator;
    }

    @PostMapping("/{projectId}/validate")
    public Mono<ResponseEntity<Map<String, Object>>> validateOntology(@PathVariable String projectId) {
        return validator.validateOntology(projectId)
                .map(result -> ResponseEntity.ok(Map.of("success", true, "validation", result)))
                .defaultIfEmpty(ResponseEntity.ok(Map.of("success", false, "error", "Validation failed")));
    }

    @PostMapping("/validate/{projectId}")
    public Mono<ResponseEntity<Map<String, Object>>> legacyValidate(@PathVariable String projectId) {
        return validateOntology(projectId);
    }
}