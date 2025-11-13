package self.research.ontology.owlEditor.service;

import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.model.SwrlRuleEntity;
import self.research.ontology.owlEditor.repository.SwrlRuleRepository;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class SwrlService {
    
    private final SwrlRuleRepository repo;

    public SwrlService(SwrlRuleRepository repo) { 
        this.repo = repo; 
    }

    public List<SwrlRuleEntity> list(String projectId) {
        return repo.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    public SwrlRuleEntity create(String projectId, Map<String,Object> ruleData) {
        SwrlRuleEntity entity = new SwrlRuleEntity();
        entity.setProjectId(projectId);
        entity.setRuleName((String) ruleData.getOrDefault("ruleName", "NewRule"));
        entity.setRuleText((String) ruleData.getOrDefault("ruleText", ""));
        entity.setCategory((String) ruleData.get("category"));
        entity.setComment((String) ruleData.get("comment"));
        entity.setEnabled((Boolean) ruleData.getOrDefault("enabled", true));
        return repo.save(entity);
    }

    public SwrlRuleEntity update(String id, Map<String,Object> ruleData) {
        SwrlRuleEntity entity = repo.findById(id)
                .orElseThrow(() -> new RuntimeException("SWRL rule not found: " + id));
        
        if (ruleData.containsKey("ruleName")) {
            entity.setRuleName((String) ruleData.get("ruleName"));
        }
        if (ruleData.containsKey("ruleText")) {
            entity.setRuleText((String) ruleData.get("ruleText"));
        }
        if (ruleData.containsKey("category")) {
            entity.setCategory((String) ruleData.get("category"));
        }
        if (ruleData.containsKey("comment")) {
            entity.setComment((String) ruleData.get("comment"));
        }
        if (ruleData.containsKey("enabled")) {
            entity.setEnabled((Boolean) ruleData.get("enabled"));
        }
        
        entity.setUpdatedAt(new Date());
        return repo.save(entity);
    }

    public void delete(String id) { 
        repo.deleteById(id); 
    }

    /**
     * Simple validation - checks for non-empty text and basic syntax
     * In production, integrate with a real SWRL parser
     */
    public Map<String,Object> validate(String ruleText) {
        if (ruleText == null || ruleText.isBlank()) {
            return Map.of(
                "valid", false,
                "errorMessage", "Rule text cannot be empty"
            );
        }
        
        // Basic checks
        if (!ruleText.contains("->") && !ruleText.contains("→")) {
            return Map.of(
                "valid", false,
                "errorMessage", "Rule must contain '->' or '→' operator"
            );
        }
        
        // Check balanced parentheses
        long openParen = ruleText.chars().filter(ch -> ch == '(').count();
        long closeParen = ruleText.chars().filter(ch -> ch == ')').count();
        if (openParen != closeParen) {
            return Map.of(
                "valid", false,
                "errorMessage", "Unbalanced parentheses"
            );
        }
        
        return Map.of(
            "valid", true,
            "errorMessage", (String) null
        );
    }
}