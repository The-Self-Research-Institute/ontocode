package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.ChangeRollbackService;
import self.research.ontology.owlEditor.service.HistorySyncService;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.UndoOperations;
import self.research.ontology.owlEditor.service.UndoPreconditions;
import self.research.ontology.owlEditor.service.UndoRefusalLog;
import self.research.ontology.owlEditor.service.UserUndoService;
import self.research.ontology.owlEditor.service.UserUndoService.Identity;
import self.research.ontology.owlEditor.service.UserUndoService.Next;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin(originPatterns = "*")
public class UserUndoController {

    private final UserUndoService undoService;
    private final ChangeRollbackService rollbackService;
    private final HistorySyncService historySync;
    private final RollbackRequestSupport support;
    private final UndoPreconditions preconditions;
    private final ProjectWriteLockRegistry locks;
    private UndoOperations operations;

    public UserUndoController(UserUndoService undoService, ChangeRollbackService rollbackService,
                              HistorySyncService historySync, RollbackRequestSupport support,
                              UndoPreconditions preconditions, ProjectWriteLockRegistry locks) {
        this.undoService = undoService;
        this.rollbackService = rollbackService;
        this.historySync = historySync;
        this.support = support;
        this.preconditions = preconditions;
        this.locks = locks;
    }

    @Autowired(required = false)
    void setOperations(UndoOperations operations) {
        this.operations = operations;
    }

    @GetMapping("/{projectId}/undo/next")
    public ResponseEntity<Map<String, Object>> next(@PathVariable String projectId,
                                                    @RequestParam(defaultValue = "false") boolean draft,
                                                    HttpServletRequest request) {
        Identity who = UndoIdentityResolver.resolve(support, request, null);
        if (who == null) {
            return unauthorized();
        }
        return ResponseEntity.ok(state(projectId, draft, who));
    }

    @PostMapping("/{projectId}/undo")
    public ResponseEntity<Map<String, Object>> undo(@PathVariable String projectId,
                                                    @RequestParam(defaultValue = "false") boolean draft,
                                                    @RequestParam(required = false) String expectedChangeId,
                                                    @RequestParam(required = false) String opId,
                                                    @RequestBody(required = false) Map<String, Object> body,
                                                    HttpServletRequest request) {
        Identity who = UndoIdentityResolver.resolve(support, request, body);
        if (who == null) {
            return unauthorized();
        }
        Optional<ChangeRollbackService.Result> repeat = repeatOf(projectId, opId, ChangeRollbackService.UNDO, who);
        if (repeat.isPresent()) {
            return finish(projectId, draft, who, repeat.get());
        }
        ChangeRollbackService.Actor actor = new ChangeRollbackService.Actor(who.primary(), who.primary(), opId);
        try {
            return locks.runExclusive(projectId, () -> {
                Optional<ChangeRollbackService.Result> earlier = repeatOf(projectId, opId, ChangeRollbackService.UNDO, who);
                if (earlier.isPresent()) {
                    return finish(projectId, draft, who, earlier.get());
                }
                Next next = undoService.nextUndo(projectId, draft, who);
                if (!next.available()) {
                    return refused(projectId, draft, who, next);
                }
                if (expectedChangeId != null && !expectedChangeId.equals(next.changeId())) {
                    return changed(projectId, draft, who, ChangeRollbackService.UNDO, next.changeId());
                }
                HistoryChange target = historySync.getHistoryChange(next.changeId());
                if (target == null) {
                    return changed(projectId, draft, who, ChangeRollbackService.UNDO, next.changeId());
                }
                List<HistoryChange> scope = next.changeSetId() == null
                        ? List.of(target) : historySync.getChangeSet(projectId, next.changeSetId());
                ResponseEntity<Map<String, Object>> denied = support.denyIfNotAllowed(scope, projectId, request);
                if (denied != null) {
                    return denied;
                }
                Optional<String> violation = preconditions.violation(projectId, scope);
                if (violation.isPresent()) {
                    return changedUnderneath(projectId, draft, who, violation.get(), next.changeId());
                }
                ChangeRollbackService.Result result = next.changeSetId() != null
                        ? rollbackService.undoChangeSet(projectId, next.changeSetId(), actor, false)
                        : rollbackService.undoEntry(projectId, next.changeId(), actor, false);
                return finish(projectId, draft, who, result);
            });
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "Couldn't undo: " + e.getMessage()));
        }
    }

    @PostMapping("/{projectId}/redo")
    public ResponseEntity<Map<String, Object>> redo(@PathVariable String projectId,
                                                    @RequestParam(defaultValue = "false") boolean draft,
                                                    @RequestParam(required = false) String expectedAuditId,
                                                    @RequestParam(required = false) String opId,
                                                    @RequestBody(required = false) Map<String, Object> body,
                                                    HttpServletRequest request) {
        Identity who = UndoIdentityResolver.resolve(support, request, body);
        if (who == null) {
            return unauthorized();
        }
        Optional<ChangeRollbackService.Result> repeat = repeatOf(projectId, opId, ChangeRollbackService.REDO, who);
        if (repeat.isPresent()) {
            return finish(projectId, draft, who, repeat.get());
        }
        ChangeRollbackService.Actor actor = new ChangeRollbackService.Actor(who.primary(), who.primary(), opId);
        try {
            return locks.runExclusive(projectId, () -> {
                Optional<ChangeRollbackService.Result> earlier = repeatOf(projectId, opId, ChangeRollbackService.REDO, who);
                if (earlier.isPresent()) {
                    return finish(projectId, draft, who, earlier.get());
                }
                Next next = undoService.nextRedo(projectId, draft, who);
                if (!next.available()) {
                    return refused(projectId, draft, who, next);
                }
                if (expectedAuditId != null && !expectedAuditId.equals(next.auditId())) {
                    return changed(projectId, draft, who, ChangeRollbackService.REDO, next.auditId());
                }
                ResponseEntity<Map<String, Object>> denied = support.denyIfNotAllowed(
                        rollbackService.entriesForUndo(projectId, next.auditId()), projectId, request);
                if (denied != null) {
                    return denied;
                }
                ChangeRollbackService.Result result = rollbackService.redoUndo(projectId, next.auditId(), actor, false);
                return finish(projectId, draft, who, result);
            });
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "error", "Couldn't redo: " + e.getMessage()));
        }
    }

    private Optional<ChangeRollbackService.Result> repeatOf(String projectId, String opId, String direction, Identity who) {
        return operations == null ? Optional.empty() : operations.repeat(projectId, opId, direction, who.primary());
    }

    private ResponseEntity<Map<String, Object>> finish(String projectId, boolean draft, Identity who,
                                                       ChangeRollbackService.Result result) {
        ResponseEntity<Map<String, Object>> response = RollbackRequestSupport.toBody(result, null, null);
        if (response.getStatusCode().value() >= 400) {
            UndoRefusalLog.record(projectId, who, result.direction(), result.scopeId(),
                    String.valueOf(response.getBody().get("message")));
        }
        Map<String, Object> body = new HashMap<>(response.getBody());
        body.putAll(state(projectId, draft, who));
        return ResponseEntity.status(response.getStatusCode()).body(body);
    }

    private ResponseEntity<Map<String, Object>> refused(String projectId, boolean draft, Identity who, Next next) {
        UndoRefusalLog.record(projectId, who, next.direction(), next.changeId(), next.reason());
        Map<String, Object> body = new HashMap<>(state(projectId, draft, who));
        body.put("success", false);
        body.put("error", next.reason());
        body.put("dependents", next.dependents());
        return ResponseEntity.status(409).body(body);
    }

    private ResponseEntity<Map<String, Object>> changedUnderneath(String projectId, boolean draft, Identity who,
                                                                  String reason, String changeId) {
        UndoRefusalLog.record(projectId, who, ChangeRollbackService.UNDO, changeId, reason);
        Map<String, Object> body = new HashMap<>(state(projectId, draft, who));
        body.put("success", false);
        body.put("error", "Nothing was undone: " + reason);
        return ResponseEntity.status(409).body(body);
    }

    private ResponseEntity<Map<String, Object>> changed(String projectId, boolean draft, Identity who,
                                                        String direction, String targetId) {
        UndoRefusalLog.record(projectId, who, direction, targetId, "history changed since the menu was shown");
        Map<String, Object> body = new HashMap<>(state(projectId, draft, who));
        body.put("success", false);
        body.put("error", "Your history changed since the menu was shown. Try again.");
        return ResponseEntity.status(409).body(body);
    }

    private Map<String, Object> state(String projectId, boolean draft, Identity who) {
        Map<String, Object> state = new HashMap<>();
        state.put("undo", undoService.nextUndo(projectId, draft, who));
        state.put("redo", undoService.nextRedo(projectId, draft, who));
        return state;
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("success", false, "error", "Sign in to undo or redo"));
    }
}
