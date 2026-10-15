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
import self.research.ontology.owlEditor.model.FuzzyRuleEntity;
import self.research.ontology.owlEditor.repository.FuzzyRuleRepository;
import self.research.ontology.owlEditor.service.DocumentHistory;
import self.research.ontology.owlEditor.service.DocumentSnapshots;
import self.research.ontology.owlEditor.service.DocumentStore;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.RequestActor;

import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/fuzzy")
@CrossOrigin
public class FuzzyRuleController {

    private final FuzzyRuleRepository ruleRepository;
    private final DocumentHistory documentHistory;
    private final DocumentSnapshots snapshots;
    private final ProjectWriteLockRegistry locks;

    public FuzzyRuleController(FuzzyRuleRepository ruleRepository, DocumentHistory documentHistory,
                               DocumentSnapshots snapshots, ProjectWriteLockRegistry locks) {
        this.ruleRepository = ruleRepository;
        this.documentHistory = documentHistory;
        this.snapshots = snapshots;
        this.locks = locks;
    }

    private static String describe(FuzzyRuleEntity entity) {
        return "IF " + entity.getCondition() + " THEN " + entity.getAction();
    }

    private void record(String projectId, RequestActor actor, String operation, FuzzyRuleEntity subject, String before,
                        String after, String oldText, String newText, String description) {
        documentHistory.record(projectId, actor, DocumentStore.FUZZY_RULE, before, after, operation,
                "urn:fuzzyrule:" + subject.getId(), subject.getName(), oldText, newText, description);
    }

    @GetMapping("/{projectId}/rules")
    public ResponseEntity<List<FuzzyRuleEntity>> getRules(@PathVariable String projectId) {
        return ResponseEntity.ok(ruleRepository.findByProjectId(projectId));
    }

    @PostMapping("/{projectId}/rules")
    public ResponseEntity<FuzzyRuleEntity> createRule(
            @PathVariable String projectId,
            @RequestBody FuzzyRuleRequest request,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String username,
            HttpServletRequest httpRequest) {
        FuzzyRuleEntity entity = new FuzzyRuleEntity();
        entity.setProjectId(projectId);
        entity.setName(request.name());
        entity.setCondition(request.condition());
        entity.setAction(request.action());
        entity.setEnabled(request.enabled() != null ? request.enabled() : true);
        entity.setCreatedAt(new Date());
        entity.setUpdatedAt(new Date());
        RequestActor actor = RequestActor.of(httpRequest, userId, username);
        return locks.exclusive(projectId, () -> {
            FuzzyRuleEntity saved = ruleRepository.save(entity);
            record(projectId, actor, "createFuzzyRule", saved, null, snapshots.snapshot(saved), null, describe(saved),
                    "Created fuzzy rule: " + saved.getName());
            return ResponseEntity.ok(saved);
        });
    }

    @PutMapping("/{projectId}/rules/{ruleId}")
    public ResponseEntity<FuzzyRuleEntity> updateRule(
            @PathVariable String projectId,
            @PathVariable String ruleId,
            @RequestBody FuzzyRuleRequest request,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String username,
            HttpServletRequest httpRequest) {
        RequestActor actor = RequestActor.of(httpRequest, userId, username);
        return locks.exclusive(projectId, () -> ruleRepository.findById(ruleId)
                .map(entity -> {
                    String oldDesc = describe(entity);
                    String before = snapshots.snapshot(entity);
                    if (request.name() != null) entity.setName(request.name());
                    if (request.condition() != null) entity.setCondition(request.condition());
                    if (request.action() != null) entity.setAction(request.action());
                    if (request.enabled() != null) entity.setEnabled(request.enabled());
                    entity.setUpdatedAt(new Date());
                    FuzzyRuleEntity saved = ruleRepository.save(entity);
                    record(projectId, actor, "updateFuzzyRule", saved, before,
                            snapshots.snapshot(saved), oldDesc, describe(saved), "Updated fuzzy rule: " + saved.getName());
                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build()));
    }

    @DeleteMapping("/{projectId}/rules/{ruleId}")
    public ResponseEntity<?> deleteRule(
            @PathVariable String projectId,
            @PathVariable String ruleId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String username,
            HttpServletRequest httpRequest) {
        RequestActor actor = RequestActor.of(httpRequest, userId, username);
        return locks.<ResponseEntity<?>>exclusive(projectId, () -> ruleRepository.findById(ruleId)
                .<ResponseEntity<?>>map(entity -> {
                    String oldDesc = describe(entity);
                    String before = snapshots.snapshot(entity);
                    ruleRepository.deleteById(ruleId);
                    record(projectId, actor, "deleteFuzzyRule", entity, before, null, oldDesc, null,
                            "Deleted fuzzy rule: " + entity.getName());
                    return ResponseEntity.ok(Map.of("success", true));
                })
                .orElse(ResponseEntity.notFound().build()));
    }

    public record FuzzyRuleRequest(String name, String condition, String action, Boolean enabled) {}
}
