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
import self.research.ontology.owlEditor.service.DatatypeDefinitionDraftService;
import self.research.ontology.owlEditor.service.DatatypeDefinitionService;
import self.research.ontology.owlEditor.service.DraftNotReadyException;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.DocumentHistory;
import self.research.ontology.owlEditor.service.DocumentSnapshots;
import self.research.ontology.owlEditor.service.DocumentStore;
import self.research.ontology.owlEditor.service.ExactChangeRecorder;
import self.research.ontology.owlEditor.service.OntologyMutationService.MutationOp;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.RequestActor;
import self.research.ontology.owlEditor.service.collaboration.CollaborativeEditService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/ontology/datatypes/definitions")
@CrossOrigin
public class DatatypeDefinitionController {

    private final DatatypeDefinitionService definitionService;
    private final DatatypeDefinitionDraftService drafts;
    private final DraftTrackingService draftTracking;
    private final CollaborativeEditService collaborativeEditService;
    private final ExactChangeRecorder exactRecorder;
    private final DocumentHistory documentHistory;
    private final DocumentSnapshots snapshots;
    private final ProjectWriteLockRegistry locks;

    public DatatypeDefinitionController(DatatypeDefinitionService definitionService,
                                        DatatypeDefinitionDraftService drafts,
                                        DraftTrackingService draftTracking,
                                        CollaborativeEditService collaborativeEditService,
                                        ExactChangeRecorder exactRecorder,
                                        DocumentHistory documentHistory,
                                        DocumentSnapshots snapshots,
                                        ProjectWriteLockRegistry locks) {
        this.definitionService = definitionService;
        this.drafts = drafts;
        this.draftTracking = draftTracking;
        this.collaborativeEditService = collaborativeEditService;
        this.exactRecorder = exactRecorder;
        this.documentHistory = documentHistory;
        this.snapshots = snapshots;
        this.locks = locks;
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

    private static RequestActor draftActor(HttpServletRequest request, String userId, String username) {
        String scoped = RequestActor.forScope(request, userId, true);
        return new RequestActor(scoped == null || scoped.isBlank() ? "anonymous" : scoped,
                RequestActor.displayName(request, username));
    }

    private ResponseEntity<?> draftChange(String projectId, RequestActor actor, List<MutationOp> ops,
                                          Supplier<Optional<DatatypeDefinitionDraftService.Change>> apply,
                                          String operation, String datatypeIri, String description) {
        AtomicReference<Optional<DatatypeDefinitionDraftService.Change>> result = new AtomicReference<>(Optional.empty());
        try {
            locks.<String>exclusive(projectId, () -> exactRecorder.recordAround(projectId, true, actor.userId(), ops, () -> {
                result.set(apply.get());
                if (result.get().isPresent()) {
                    draftTracking.recordDrafts(projectId, actor.userId(), actor.username(), ops, UUID.randomUUID().toString());
                }
            }, () -> result.get().map(change -> documentHistory.record(projectId, actor, DocumentStore.DATATYPE_DEFINITION,
                    snapshots.snapshot(change.before()), snapshots.snapshot(change.after()), operation, datatypeIri,
                    datatypeIri, change.before() != null ? change.before().getExpression() : null,
                    change.after() != null ? change.after().getExpression() : null, description, true)).orElse(null)));
        } catch (DraftNotReadyException e) {
            return ResponseEntity.status(409).body(Map.of("success", false, "error", e.getMessage()));
        }
        if (result.get().isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Definition not found"));
        }
        DatatypeDefinitionEntity after = result.get().get().after();
        return after == null ? ResponseEntity.ok(Map.of("success", true))
                : ResponseEntity.ok(Map.of("success", true, "data", after));
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<?> listDefinitions(@PathVariable String projectId,
                                             @RequestParam String datatypeIri,
                                             @RequestParam(defaultValue = "false") boolean draft,
                                             @RequestParam(required = false) String userId,
                                             HttpServletRequest httpRequest) {
        List<DatatypeDefinitionEntity> definitions = draft
                ? drafts.list(projectId, datatypeIri, draftActor(httpRequest, userId, null).userId())
                : definitionService.listDefinitions(projectId, datatypeIri);
        return ResponseEntity.ok(Map.of("success", true, "data", definitions));
    }

    @PostMapping("/{projectId}")
    public ResponseEntity<?> createDefinition(@PathVariable String projectId,
                                              @RequestBody CreateDatatypeDefinitionRequest request,
                                              @RequestParam(defaultValue = "false") boolean draft,
                                              @RequestParam(required = false) String userId,
                                              @RequestParam(required = false) String username,
                                              HttpServletRequest httpRequest) {
        if (request == null || isBlank(request.datatypeIri) || isBlank(request.expression)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "datatypeIri and expression are required"));
        }
        String type = isBlank(request.definitionType) ? "expression" : request.definitionType;
        if (draft) {
            RequestActor draftActor = draftActor(httpRequest, userId, username);
            return draftChange(projectId, draftActor, List.of(op("addDatatypeDefinition", request.datatypeIri)),
                    () -> Optional.of(drafts.create(projectId, draftActor.userId(), request.datatypeIri,
                            request.expression, type)),
                    "addDatatypeDefinition", request.datatypeIri, "Added a definition to " + request.datatypeIri);
        }
        RequestActor actor = RequestActor.of(httpRequest, userId, username);
        AtomicReference<DatatypeDefinitionEntity> created = new AtomicReference<>();
        locks.<String>exclusive(projectId, () -> recordAround(projectId, actor, List.of(op("addDatatypeDefinition", request.datatypeIri)),
                () -> created.set(definitionService.createDefinition(projectId, request.datatypeIri, request.expression, type)),
                "addDatatypeDefinition", request.datatypeIri, null, () -> snapshots.snapshot(created.get()),
                null, request.expression, "Added a definition to " + request.datatypeIri));
        broadcastDatatypeChange(projectId, request.datatypeIri, "addDatatypeDefinition", actor);
        return ResponseEntity.ok(Map.of("success", true, "data", created.get()));
    }

    @PutMapping("/{projectId}/{definitionId}")
    public ResponseEntity<?> updateDefinition(@PathVariable String projectId,
                                              @PathVariable String definitionId,
                                              @RequestBody UpdateDatatypeDefinitionRequest request,
                                              @RequestParam(defaultValue = "false") boolean draft,
                                              @RequestParam(required = false) String userId,
                                              @RequestParam(required = false) String username,
                                              HttpServletRequest httpRequest) {
        if (draft) {
            RequestActor draftActor = draftActor(httpRequest, userId, username);
            Optional<DatatypeDefinitionEntity> visible = drafts.findVisible(projectId, draftActor.userId(), definitionId);
            if (visible.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("success", false, "error", "Definition not found"));
            }
            String iri = visible.get().getDatatypeIri();
            return draftChange(projectId, draftActor,
                    List.of(op("deleteDatatypeDefinition", iri), op("addDatatypeDefinition", iri)),
                    () -> drafts.update(projectId, draftActor.userId(), definitionId,
                            request != null ? request.expression : null, request != null ? request.definitionType : null),
                    "updateDatatypeDefinition", iri, "Updated a definition of " + iri);
        }
        RequestActor actor = RequestActor.of(httpRequest, userId, username);
        return locks.<ResponseEntity<?>>exclusive(projectId, () -> {
            Optional<DatatypeDefinitionEntity> current = definitionService.findById(projectId, definitionId);
            if (current.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("success", false, "error", "Definition not found"));
            }
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
        });
    }

    @DeleteMapping("/{projectId}/{definitionId}")
    public ResponseEntity<?> deleteDefinition(@PathVariable String projectId,
                                              @PathVariable String definitionId,
                                              @RequestParam(defaultValue = "false") boolean draft,
                                              @RequestParam(required = false) String userId,
                                              @RequestParam(required = false) String username,
                                              HttpServletRequest httpRequest) {
        if (draft) {
            RequestActor draftActor = draftActor(httpRequest, userId, username);
            Optional<DatatypeDefinitionEntity> visible = drafts.findVisible(projectId, draftActor.userId(), definitionId);
            if (visible.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("success", false, "error", "Definition not found"));
            }
            String iri = visible.get().getDatatypeIri();
            return draftChange(projectId, draftActor, List.of(op("deleteDatatypeDefinition", iri)),
                    () -> drafts.delete(projectId, draftActor.userId(), definitionId),
                    "deleteDatatypeDefinition", iri, "Deleted a definition of " + iri);
        }
        RequestActor actor = RequestActor.of(httpRequest, userId, username);
        return locks.<ResponseEntity<?>>exclusive(projectId, () -> {
            Optional<DatatypeDefinitionEntity> toDelete = definitionService.findById(projectId, definitionId);
            if (toDelete.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("success", false, "error", "Definition not found"));
            }
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
        });
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
