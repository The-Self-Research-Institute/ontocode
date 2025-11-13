package self.research.ontology.owlEditor.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.owlEditor.model.SwrlRuleEntity;
import self.research.ontology.owlEditor.service.SwrlService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/swrl/{projectId}")
@CrossOrigin(origins = "*")
public class SwrlController {

    private final SwrlService service;

    public SwrlController(SwrlService service) { 
        this.service = service; 
    }

    @GetMapping("/rules")
    public ResponseEntity<List<SwrlRuleEntity>> listRules(@PathVariable String projectId) {
        return ResponseEntity.ok(service.list(projectId));
    }

    @PostMapping("/rules")
    public ResponseEntity<SwrlRuleEntity> createRule(
            @PathVariable String projectId, 
            @RequestBody Map<String,Object> body) {
        return ResponseEntity.ok(service.create(projectId, body));
    }

    @PutMapping("/rules/{id}")
    public ResponseEntity<SwrlRuleEntity> updateRule(
            @PathVariable String projectId, 
            @PathVariable String id, 
            @RequestBody Map<String,Object> body) {
        return ResponseEntity.ok(service.update(id, body));
    }

    @DeleteMapping("/rules/{id}")
    public ResponseEntity<Map<String,Object>> deleteRule(
            @PathVariable String projectId, 
            @PathVariable String id) {
        service.delete(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/validate")
    public ResponseEntity<Map<String,Object>> validateRule(
            @PathVariable String projectId, 
            @RequestBody Map<String,String> body) {
        return ResponseEntity.ok(service.validate(body.getOrDefault("ruleText", "")));
    }

    @PostMapping("/execute")
    public ResponseEntity<Map<String,Object>> executeRules(@PathVariable String projectId) {
        // Stub: Integrate a SWRL engine (e.g., SWRLTab, OWLAPI SWRL support) here
        List<SwrlRuleEntity> enabledRules = service.list(projectId).stream()
                .filter(SwrlRuleEntity::isEnabled)
                .toList();
        
        return ResponseEntity.ok(Map.of(
            "success", true,
            "executionTimeMs", 10,
            "totalRulesExecuted", enabledRules.size(),
            "inferredAxiomsCount", 0,
            "inferredAxioms", List.of()
        ));
    }

    @GetMapping("/rules/export")
    public ResponseEntity<List<SwrlRuleEntity>> exportRules(@PathVariable String projectId) {
        return ResponseEntity.ok(service.list(projectId));
    }

    @PostMapping("/cache/clear")
    public ResponseEntity<Map<String,Object>> clearCache(@PathVariable String projectId) {
        // Stub hook for future cache usage
        return ResponseEntity.ok(Map.of("success", true));
    }
}