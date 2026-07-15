package self.research.ontology.owlEditor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.service.AxiomAnnotationService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin(originPatterns = "*")
public class AxiomAnnotationController {

    private static final Logger log = LoggerFactory.getLogger(AxiomAnnotationController.class);

    private final AxiomAnnotationService axiomAnnotationService;

    public AxiomAnnotationController(AxiomAnnotationService axiomAnnotationService) {
        this.axiomAnnotationService = axiomAnnotationService;
    }

    @GetMapping("/{projectId}/axiom-annotations")
    public ResponseEntity<?> getAnnotations(@PathVariable String projectId,
                                            @RequestParam String entityIri,
                                            @RequestParam String relatedIri,
                                            @RequestParam(required = false) String sectionName,
                                            @RequestParam(required = false, defaultValue = "false") boolean draft,
                                            @RequestParam(required = false) String userId) {
        try {
            List<Map<String, String>> annotations = axiomAnnotationService.getAnnotations(
                    projectId, entityIri, relatedIri, sectionName, draft, userId);
            return ResponseEntity.ok(Map.of("success", true, "annotations", annotations));
        } catch (Exception e) {
            log.error("Failed to get axiom annotations for project {}", projectId, e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage() != null ? e.getMessage() : "Failed to load axiom annotations"));
        }
    }

    @PostMapping("/{projectId}/axiom-annotations")
    public ResponseEntity<?> addAnnotation(@PathVariable String projectId,
                                           @RequestBody AxiomAnnotationRequest request,
                                           @RequestParam(required = false, defaultValue = "false") boolean draft,
                                           @RequestParam(required = false) String userId) {
        try {
            axiomAnnotationService.addAnnotation(
                    projectId,
                    request.entityIri,
                    request.relatedIri,
                    request.sectionName,
                    request.annotationProperty,
                    request.value,
                    request.language,
                    draft,
                    userId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to add axiom annotation for project {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage() != null ? e.getMessage() : "Failed to add axiom annotation"));
        }
    }

    @DeleteMapping("/{projectId}/axiom-annotations")
    public ResponseEntity<?> deleteAnnotation(@PathVariable String projectId,
                                              @RequestParam String entityIri,
                                              @RequestParam String relatedIri,
                                              @RequestParam String annotationProperty,
                                              @RequestParam String value,
                                              @RequestParam(required = false) String sectionName,
                                              @RequestParam(required = false, defaultValue = "false") boolean draft,
                                              @RequestParam(required = false) String userId) {
        try {
            axiomAnnotationService.deleteAnnotation(
                    projectId, entityIri, relatedIri, sectionName, annotationProperty, value, draft, userId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to delete axiom annotation for project {}", projectId, e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", e.getMessage() != null ? e.getMessage() : "Failed to delete axiom annotation"));
        }
    }

    public static class AxiomAnnotationRequest {
        public String entityIri;
        public String relatedIri;
        public String sectionName;
        public String annotationProperty;
        public String value;
        public String language;
    }
}
