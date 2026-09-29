package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.service.ChangeRollbackService;
import self.research.ontology.owlEditor.service.HistorySyncService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology")
@CrossOrigin(originPatterns = "*")
public class ChangeSetController {

    private final ChangeRollbackService rollbackService;
    private final HistorySyncService historySyncService;
    private final RollbackRequestSupport support;

    public ChangeSetController(ChangeRollbackService rollbackService, HistorySyncService historySyncService,
                               RollbackRequestSupport support) {
        this.rollbackService = rollbackService;
        this.historySyncService = historySyncService;
        this.support = support;
    }

    @PostMapping("/{projectId}/change-sets/{changeSetId}/undo")
    public ResponseEntity<Map<String, Object>> undo(@PathVariable String projectId, @PathVariable String changeSetId,
                                                    @RequestParam(defaultValue = "false") boolean dryRun,
                                                    @RequestBody(required = false) Map<String, Object> body,
                                                    HttpServletRequest request) {
        return run(projectId, changeSetId, dryRun, body, request, true);
    }

    @PostMapping("/{projectId}/change-sets/{changeSetId}/redo")
    public ResponseEntity<Map<String, Object>> redo(@PathVariable String projectId, @PathVariable String changeSetId,
                                                    @RequestParam(defaultValue = "false") boolean dryRun,
                                                    @RequestBody(required = false) Map<String, Object> body,
                                                    HttpServletRequest request) {
        return run(projectId, changeSetId, dryRun, body, request, false);
    }

    @PostMapping("/{projectId}/undos/{auditId}/redo")
    public ResponseEntity<Map<String, Object>> redoUndo(@PathVariable String projectId, @PathVariable String auditId,
                                                        @RequestParam(defaultValue = "false") boolean dryRun,
                                                        @RequestBody(required = false) Map<String, Object> body,
                                                        HttpServletRequest request) {
        List<HistoryChange> entries = rollbackService.entriesForUndo(projectId, auditId);
        if (entries.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Undo not found"));
        }
        ResponseEntity<Map<String, Object>> denied = support.denyIfNotAllowed(entries, projectId, request);
        if (denied != null) {
            return denied;
        }
        ChangeRollbackService.Result result = rollbackService.redoUndo(projectId, auditId, support.actor(request, body), dryRun);
        ResponseEntity<Map<String, Object>> response = RollbackRequestSupport.toBody(result, null, null);
        response.getBody().put("auditId", auditId);
        return response;
    }

    private ResponseEntity<Map<String, Object>> run(String projectId, String changeSetId, boolean dryRun,
                                                    Map<String, Object> body, HttpServletRequest request, boolean undo) {
        List<HistoryChange> entries = historySyncService.getChangeSet(projectId, changeSetId);
        if (entries.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Change set not found"));
        }
        ResponseEntity<Map<String, Object>> denied = support.denyIfNotAllowed(entries, projectId, request);
        if (denied != null) {
            return denied;
        }
        ChangeRollbackService.Actor actor = support.actor(request, body);
        ChangeRollbackService.Result result = undo
                ? rollbackService.undoChangeSet(projectId, changeSetId, actor, dryRun)
                : rollbackService.redoChangeSet(projectId, changeSetId, actor, dryRun);
        ResponseEntity<Map<String, Object>> response = RollbackRequestSupport.toBody(result, null, null);
        response.getBody().put("changeSetId", changeSetId);
        return response;
    }
}
