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
import org.springframework.web.bind.annotation.RestController;
import self.research.ontology.owlEditor.model.FuzzyRuleEntity;
import self.research.ontology.owlEditor.repository.FuzzyRuleRepository;

import java.util.Date;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/fuzzy")
@CrossOrigin
public class FuzzyRuleController {

    private final FuzzyRuleRepository ruleRepository;

    public FuzzyRuleController(FuzzyRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @GetMapping("/{projectId}/rules")
    public ResponseEntity<List<FuzzyRuleEntity>> getRules(@PathVariable String projectId) {
        return ResponseEntity.ok(ruleRepository.findByProjectId(projectId));
    }

    @PostMapping("/{projectId}/rules")
    public ResponseEntity<FuzzyRuleEntity> createRule(
            @PathVariable String projectId,
            @RequestBody FuzzyRuleRequest request) {
        FuzzyRuleEntity entity = new FuzzyRuleEntity();
        entity.setProjectId(projectId);
        entity.setName(request.name());
        entity.setCondition(request.condition());
        entity.setAction(request.action());
        entity.setEnabled(request.enabled() != null ? request.enabled() : true);
        entity.setCreatedAt(new Date());
        entity.setUpdatedAt(new Date());
        return ResponseEntity.ok(ruleRepository.save(entity));
    }

    @PutMapping("/{projectId}/rules/{ruleId}")
    public ResponseEntity<FuzzyRuleEntity> updateRule(
            @PathVariable String projectId,
            @PathVariable String ruleId,
            @RequestBody FuzzyRuleRequest request) {
        return ruleRepository.findById(ruleId)
                .map(entity -> {
                    if (request.name() != null) entity.setName(request.name());
                    if (request.condition() != null) entity.setCondition(request.condition());
                    if (request.action() != null) entity.setAction(request.action());
                    if (request.enabled() != null) entity.setEnabled(request.enabled());
                    entity.setUpdatedAt(new Date());
                    return ResponseEntity.ok(ruleRepository.save(entity));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{projectId}/rules/{ruleId}")
    public ResponseEntity<?> deleteRule(
            @PathVariable String projectId,
            @PathVariable String ruleId) {
        if (ruleRepository.existsById(ruleId)) {
            ruleRepository.deleteById(ruleId);
            return ResponseEntity.ok(Map.of("success", true));
        }
        return ResponseEntity.notFound().build();
    }

    public record FuzzyRuleRequest(String name, String condition, String action, Boolean enabled) {}
}
