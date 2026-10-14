package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
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
import self.research.ontology.owlEditor.service.DocumentHistory;
import self.research.ontology.owlEditor.service.DocumentSnapshots;
import self.research.ontology.owlEditor.service.DocumentStore;
import self.research.ontology.owlEditor.service.ExactChangeRecorder;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.RequestActor;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/ontology/datatypes/definitions")
@CrossOrigin
public class DatatypeDefinitionController {

    private final DatatypeDefinitionService definitionService;
    private final CollaborativeEditService collaborativeEditService;
    private final ExactChangeRecorder exactRecorder;
    private final DocumentHistory documentHistory;
    private final DocumentSnapshots snapshots;

    public DatatypeDefinitionController(DatatypeDefinitionService definitionService,
                                        CollaborativeEditService collaborativeEditService,
                                        ExactChangeRecorder exactRecorder,
                                        DocumentHistory documentHistory,
                                        DocumentSnapshots snapshots) {
        this.definitionService = definitionService;
        this.collaborativeEditService = collaborativeEditService;
        this.exactRecorder = exactRecorder;
        this.documentHistory = documentHistory;
        this.snapshots = snapshots;
    }

    private void broadcastDatatypeChange(String projectId, String datatypeIri, String mutationType, RequestActor actor) {
        collaborativeEditService.broadcastMutation(projectId,
            new MutationOp(mutationType, datatypeIri, null, null, null, null, null, null, null, null, null, null, null, null, null),
            actor.userId(), actor.username());
    }

    private static MutationOp op(String type, String datatypeIri) {
        return new MutationOp(type, datatypeIri, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private String recordAround(String projectId, RequestActor actor, List<MutationOp> ops, Runnable apply, String operation,
                                String datatypeIri, String before, java.util.function.Supplier<String> after, String oldText,
                                String newText, String description) {
        return exactRecorder.recordAround(projectId, false, actor.userId(), ops, apply, () -> {
            String afterJson = after.get();
            return documentHistory.record(projectId, actor, DocumentStore.DATATYPE_DEFINITION, before, afterJson, operation,
                    datatypeIri, datatypeIri, oldText, newText, description);
        });
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
                                              @RequestParam(required = false) String username,
                                              HttpServletRequest httpRequest) {
        if (request == null || isBlank(request.datatypeIri) || isBlank(request.expression)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "datatypeIri and expression are required"));
        }
        RequestActor actor = RequestActor.of(httpRequest, userId, username);
        String type = isBlank(request.definitionType) ? "expression" : request.definitionType;
        AtomicReference<DatatypeDefinitionEntity> created = new AtomicReference<>();
        recordAround(projectId, actor, List.of(op("addDatatypeDefinition", request.datatypeIri)),
                () -> created.set(definitionService.createDefinition(projectId, request.datatypeIri, request.expression, type)),
                "addDatatypeDefinition", request.datatypeIri, null, () -> snapshots.snapshot(created.get()),
                null, request.expression, "Added a definition to " + request.datatypeIri);
        broadcastDatatypeChange(projectId, request.datatypeIri, "addDatatypeDefinition", actor);
        return ResponseEntity.ok(Map.of("success", true, "data", created.get()));
    }

    @PutMapping("/{projectId}/{definitionId}")
    public ResponseEntity<?> updateDefinition(@PathVariable String projectId,
                                              @PathVariable String definitionId,
                                              @RequestBody UpdateDatatypeDefinitionRequest request,
                                              @RequestParam(required = false) String userId,
                                              @RequestParam(required = false) String username,
                                              HttpServletRequest httpRequest) {
        Optional<DatatypeDefinitionEntity> current = definitionService.findById(projectId, definitionId);
        if (current.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Definition not found"));
        }
        RequestActor actor = RequestActor.of(httpRequest, userId, username);
        String datatypeIri = current.get().getDatatypeIri();
        String before = snapshots.snapshot(current.get());
        String oldExpression = current.get().getExpression();
        AtomicReference<Optional<DatatypeDefinitionEntity>> updated = new AtomicReference<>(Optional.empty());
        recordAround(projectId, actor, List.of(op("deleteDatatypeDefinition", datatypeIri), op("addDatatypeDefinition", datatypeIri)),
                () -> updated.set(definitionService.updateDefinition(projectId, definitionId,
                        request != null ? request.expression : null, request != null ? request.definitionType : null)),
                "updateDatatypeDefinition", datatypeIri, before,
                () -> updated.get().map(snapshots::snapshot).orElse(null),
                oldExpression, request != null && !isBlank(request.expression) ? request.expression : oldExpression,
                "Updated a definition of " + datatypeIri);
        if (updated.get().isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Definition not found"));
        }
        broadcastDatatypeChange(projectId, datatypeIri, "updateDatatypeDefinition", actor);
        return ResponseEntity.ok(Map.of("success", true, "data", updated.get().get()));
    }

    @DeleteMapping("/{projectId}/{definitionId}")
    public ResponseEntity<?> deleteDefinition(@PathVariable String projectId,
                                              @PathVariable String definitionId,
                                              @RequestParam(required = false) String userId,
                                              @RequestParam(required = false) String username,
                                              HttpServletRequest httpRequest) {
        Optional<DatatypeDefinitionEntity> toDelete = definitionService.findById(projectId, definitionId);
        if (toDelete.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Definition not found"));
        }
        RequestActor actor = RequestActor.of(httpRequest, userId, username);
        String datatypeIri = toDelete.get().getDatatypeIri();
        String before = snapshots.snapshot(toDelete.get());
        AtomicReference<Boolean> deleted = new AtomicReference<>(false);
        recordAround(projectId, actor, List.of(op("deleteDatatypeDefinition", datatypeIri)),
                () -> deleted.set(definitionService.deleteDefinition(projectId, definitionId)),
                "deleteDatatypeDefinition", datatypeIri, before, () -> null, toDelete.get().getExpression(), null,
                "Deleted a definition of " + datatypeIri);
        if (!deleted.get()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Definition not found"));
        }
        broadcastDatatypeChange(projectId, datatypeIri, "deleteDatatypeDefinition", actor);
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
