package self.research.ontology.swrl.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import self.research.ontology.swrl.dto.*;
import self.research.ontology.swrl.model.*;
import self.research.ontology.swrl.service.SwrlEngineService;

import jakarta.validation.Valid; 
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/swrl")
@CrossOrigin(origins = "*")
public class SwrlController {

    private static final Logger logger = LoggerFactory.getLogger(SwrlController.class);

    @Autowired
    private SwrlEngineService swrlEngineService;

    @PostMapping("/{projectId}/validate")
    public ResponseEntity<ValidationResult> validateRule(
            @PathVariable String projectId,
            @RequestBody Map<String, String> request) {
        
        String ruleText = request.get("ruleText");
        ValidationResult result = swrlEngineService.validateRule(projectId, ruleText);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{projectId}/rules")
    public ResponseEntity<SwrlRule> createRule(
            @PathVariable String projectId,
            @Valid @RequestBody CreateRuleRequest request) {
        
        try {
            SwrlRule rule = swrlEngineService.createRule(
                projectId, 
                request.getRuleName(), 
                request.getRuleText(),
                request.getComment(),
                request.getCategory()
            );
            return ResponseEntity.ok(rule);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/{projectId}/rules")
    public ResponseEntity<List<SwrlRule>> getRules(@PathVariable String projectId) {
        List<SwrlRule> rules = swrlEngineService.getRules(projectId);
        return ResponseEntity.ok(rules);
    }

    @GetMapping("/{projectId}/rules/{ruleId}")
    public ResponseEntity<SwrlRule> getRule(
            @PathVariable String projectId,
            @PathVariable String ruleId) {
        
        // Implementation here
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{projectId}/rules/{ruleId}")
    public ResponseEntity<SwrlRule> updateRule(
            @PathVariable String projectId,
            @PathVariable String ruleId,
            @RequestBody UpdateRuleRequest request) {
        
        try {
            SwrlRule rule = swrlEngineService.updateRule(
                ruleId,
                request.getRuleText(),
                request.getComment(),
                request.getEnabled(),
                request.getCategory()
            );
            return ResponseEntity.ok(rule);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{projectId}/rules/{ruleId}")
    public ResponseEntity<Void> deleteRule(
            @PathVariable String projectId,
            @PathVariable String ruleId) {
        
        try {
            swrlEngineService.deleteRule(ruleId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{projectId}/execute")
    public ResponseEntity<ExecutionResponse> executeRules(@PathVariable String projectId) {
        ExecutionResult result = swrlEngineService.executeRules(projectId);
        
        ExecutionResponse response = new ExecutionResponse(
            result.isSuccess(),
            result.getExecutionTimeMs(),
            result.getInferredAxiomsCount(),
            swrlEngineService.getRules(projectId).size(),
            result.getInferredAxioms(),
            result.getErrorMessage()
        );
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{projectId}/cache/clear")
    public ResponseEntity<Void> clearCache(@PathVariable String projectId) {
        swrlEngineService.clearCache(projectId);
        return ResponseEntity.ok().build();
    }
}