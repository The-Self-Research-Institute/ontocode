package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.model.DatatypeDefinitionEntity;
import self.research.ontology.owlEditor.service.DatatypeDefinitionService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/ontology/datatypes/definitions")
@CrossOrigin
public class DatatypeDefinitionController {

    private final DatatypeDefinitionService definitionService;

    public DatatypeDefinitionController(DatatypeDefinitionService definitionService) {
        this.definitionService = definitionService;
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<?> listDefinitions(@PathVariable String projectId,
                                             @RequestParam String datatypeIri) {
        List<DatatypeDefinitionEntity> definitions = definitionService.listDefinitions(projectId, datatypeIri);
        return ResponseEntity.ok(Map.of("success", true, "data", definitions));
    }

    @PostMapping("/{projectId}")
    public ResponseEntity<?> createDefinition(@PathVariable String projectId,
                                              @RequestBody CreateDatatypeDefinitionRequest request) {
        if (request == null || isBlank(request.datatypeIri) || isBlank(request.expression)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "datatypeIri and expression are required"));
        }
        String type = isBlank(request.definitionType) ? "expression" : request.definitionType;
        DatatypeDefinitionEntity created = definitionService.createDefinition(projectId, request.datatypeIri, request.expression, type);
        return ResponseEntity.ok(Map.of("success", true, "data", created));
    }

    @PutMapping("/{projectId}/{definitionId}")
    public ResponseEntity<?> updateDefinition(@PathVariable String projectId,
                                              @PathVariable String definitionId,
                                              @RequestBody UpdateDatatypeDefinitionRequest request) {
        Optional<DatatypeDefinitionEntity> updated = definitionService.updateDefinition(
                projectId,
                definitionId,
                request != null ? request.expression : null,
                request != null ? request.definitionType : null
        );
        if (updated.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Definition not found"));
        }
        return ResponseEntity.ok(Map.of("success", true, "data", updated.get()));
    }

    @DeleteMapping("/{projectId}/{definitionId}")
    public ResponseEntity<?> deleteDefinition(@PathVariable String projectId,
                                              @PathVariable String definitionId) {
        boolean deleted = definitionService.deleteDefinition(projectId, definitionId);
        if (!deleted) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Definition not found"));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static class CreateDatatypeDefinitionRequest {
        public String datatypeIri;
        public String expression;
        public String definitionType;
    }

    public static class UpdateDatatypeDefinitionRequest {
        public String expression;
        public String definitionType;
    }
}
