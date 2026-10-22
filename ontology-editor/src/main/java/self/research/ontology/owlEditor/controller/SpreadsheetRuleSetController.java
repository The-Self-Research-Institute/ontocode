package self.research.ontology.owlEditor.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.model.SpreadsheetRuleSetEntity;
import self.research.ontology.owlEditor.service.WorkspaceOwnershipService;
import self.research.ontology.owlEditor.service.spreadsheet.SpreadsheetRuleSetService;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleSpec;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ontology/spreadsheet-import/{projectId}/rulesets")
@CrossOrigin
public class SpreadsheetRuleSetController {

    private final SpreadsheetRuleSetService ruleSets;
    private WorkspaceOwnershipService workspaceOwnership;

    public SpreadsheetRuleSetController(SpreadsheetRuleSetService ruleSets) {
        this.ruleSets = ruleSets;
    }

    @Autowired(required = false)
    void setWorkspaceOwnership(WorkspaceOwnershipService workspaceOwnership) {
        this.workspaceOwnership = workspaceOwnership;
    }

    public record RuleSetRequest(String name, String description, List<RuleSpec> rules, String user) {
    }

    @GetMapping
    public ResponseEntity<?> list(@PathVariable String projectId) {
        return ResponseEntity.ok(Map.of("success", true, "data", ruleSets.list(projectId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String projectId, @PathVariable String id) {
        return ruleSets.get(projectId, id)
            .<ResponseEntity<?>>map(r -> ResponseEntity.ok(Map.of("success", true, "data", r)))
            .orElseGet(() -> notFound());
    }

    @PostMapping
    public ResponseEntity<?> create(@PathVariable String projectId, @RequestBody RuleSetRequest request,
                                    HttpServletRequest http) {
        try {
            SpreadsheetImportCaller.Caller caller = SpreadsheetImportCaller.resolve(http, request.user(), request.user());
            SpreadsheetRuleSetEntity saved = ruleSets.create(projectId, request.name(), request.description(),
                request.rules(), caller.username(), caller.userId());
            return ResponseEntity.ok(Map.of("success", true, "data", saved));
        } catch (SpreadsheetRuleSetService.DuplicateNameException e) {
            return ResponseEntity.status(409).body(Map.of("success", false, "error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String projectId, @PathVariable String id,
                                    @RequestBody RuleSetRequest request, HttpServletRequest http) {
        if (draftEditorChangingAnotherUsersRuleSet(projectId, id, http)) {
            return notOwner();
        }
        try {
            return ruleSets.update(projectId, id, request.name(), request.description(), request.rules(),
                    savedBy(http, request))
                .<ResponseEntity<?>>map(r -> ResponseEntity.ok(Map.of("success", true, "data", r)))
                .orElseGet(() -> notFound());
        } catch (SpreadsheetRuleSetService.DuplicateNameException e) {
            return ResponseEntity.status(409).body(Map.of("success", false, "error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String projectId, @PathVariable String id, HttpServletRequest http) {
        if (draftEditorChangingAnotherUsersRuleSet(projectId, id, http)) {
            return notOwner();
        }
        return ruleSets.delete(projectId, id)
            ? ResponseEntity.ok(Map.of("success", true))
            : notFound();
    }

    private boolean draftEditorChangingAnotherUsersRuleSet(String projectId, String id, HttpServletRequest http) {
        if (workspaceOwnership == null) {
            return false;
        }
        SpreadsheetImportCaller.Caller caller = SpreadsheetImportCaller.resolve(http, null, null);
        if (caller.userId() == null || !workspaceOwnership.isDraftEditorInProject(caller.userId(), projectId)) {
            return false;
        }
        return ruleSets.get(projectId, id)
            .map(r -> !ruleSets.isCreatedBy(r, caller.userId(), caller.username()))
            .orElse(false);
    }

    private static ResponseEntity<?> notOwner() {
        return ResponseEntity.status(403).body(Map.of("success", false,
            "error", "You can only change or delete rule sets you saved. Use Save as to keep your own copy."));
    }

    private static String savedBy(HttpServletRequest http, RuleSetRequest request) {
        return SpreadsheetImportCaller.resolve(http, request.user(), request.user()).username();
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(404).body(Map.of("success", false, "error", "Rule set not found"));
    }
}
