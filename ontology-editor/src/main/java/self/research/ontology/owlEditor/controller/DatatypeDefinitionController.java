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
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/ontology/datatypes/definitions")
@CrossOrigin
public class DatatypeDefinitionController {

    private final DatatypeDefinitionService definitionService;
    private final CollaborativeEditService collaborativeEditService;

    public DatatypeDefinitionController(DatatypeDefinitionService definitionService,
                                        CollaborativeEditService collaborativeEditService) {
        this.definitionService = definitionService;
        this.collaborativeEditService = collaborativeEditService;
    }

    private void broadcastDatatypeChange(String projectId, String datatypeIri, String mutationType,
                                         String userId, String username) {
        collaborativeEditService.broadcastMutation(projectId,
            new MutationOp(mutationType, datatypeIri, null, null, null, null, null, null, null, null, null, null),
            userId != null ? userId : "anonymous",
            username != null ? username : "Anonymous");
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<?> listDefinitions(@PathVariable String projectId,
                                             @RequestParam String datatypeIri) {
        List<DatatypeDefinitionEntity> definitions = definitionService.listDefinitions(projectId, datatypeIri);
        return ResponseEntity.ok(Map.of("success", true, "data", definitions));
    }

    @PostMapping("/{projectId}")
    public ResponseEntity<?> createDefinition(@PathVariable String projectId,
                                              @RequestBody CreateDatatypeDefinitionRequest request,
                                              @RequestParam(required = false) String userId,
                                              @RequestParam(required = false) String username) {
        if (request == null || isBlank(request.datatypeIri) || isBlank(request.expression)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "datatypeIri and expression are required"));
        }
        String type = isBlank(request.definitionType) ? "expression" : request.definitionType;
        DatatypeDefinitionEntity created = definitionService.createDefinition(projectId, request.datatypeIri, request.expression, type);
        broadcastDatatypeChange(projectId, request.datatypeIri, "addDatatypeDefinition", userId, username);
        return ResponseEntity.ok(Map.of("success", true, "data", created));
    }

    @PutMapping("/{projectId}/{definitionId}")
    public ResponseEntity<?> updateDefinition(@PathVariable String projectId,
                                              @PathVariable String definitionId,
                                              @RequestBody UpdateDatatypeDefinitionRequest request,
                                              @RequestParam(required = false) String userId,
                                              @RequestParam(required = false) String username) {
        Optional<DatatypeDefinitionEntity> updated = definitionService.updateDefinition(
                projectId,
                definitionId,
                request != null ? request.expression : null,
                request != null ? request.definitionType : null
        );
        if (updated.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Definition not found"));
        }
        updated.ifPresent(def ->
            broadcastDatatypeChange(projectId, def.getDatatypeIri(), "updateDatatypeDefinition", userId, username));
        return ResponseEntity.ok(Map.of("success", true, "data", updated.get()));
    }

    @DeleteMapping("/{projectId}/{definitionId}")
    public ResponseEntity<?> deleteDefinition(@PathVariable String projectId,
                                              @PathVariable String definitionId,
                                              @RequestParam(required = false) String userId,
                                              @RequestParam(required = false) String username) {
        Optional<DatatypeDefinitionEntity> toDelete = definitionService.findById(projectId, definitionId);
        boolean deleted = definitionService.deleteDefinition(projectId, definitionId);
        if (!deleted) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Definition not found"));
        }
        toDelete.ifPresent(def ->
            broadcastDatatypeChange(projectId, def.getDatatypeIri(), "deleteDatatypeDefinition", userId, username));
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
