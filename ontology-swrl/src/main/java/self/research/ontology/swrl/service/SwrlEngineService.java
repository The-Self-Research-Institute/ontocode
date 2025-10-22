package self.research.ontology.swrl.service;

import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.swrlapi.factory.SWRLAPIFactory;
import org.swrlapi.core.SWRLRuleEngine;
import org.swrlapi.exceptions.SWRLBuiltInException;
import org.swrlapi.parser.SWRLParseException;
import self.research.ontology.swrl.model.*;
import self.research.ontology.swrl.repository.SwrlRuleRepository;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SwrlEngineService {

    private static final Logger logger = LoggerFactory.getLogger(SwrlEngineService.class);

    @Autowired
    private OntologyClientService ontologyClient;

    @Autowired
    private SwrlRuleRepository ruleRepository;

    private final Map<String, SWRLRuleEngine> engineCache = new HashMap<>();

    public ValidationResult validateRule(String projectId, String ruleText) {
        try {
            OWLOntology ontology = ontologyClient.fetchOntology(projectId);
            SWRLRuleEngine engine = getOrCreateEngine(projectId, ontology);
            
            String tempName = "temp_validation_" + System.currentTimeMillis();
            engine.createSWRLRule(tempName, ruleText);
            engine.deleteSWRLRule(tempName);
            
            logger.info("Rule validation successful for project: {}", projectId);
            return new ValidationResult(true, null, Collections.emptyList());
            
        } catch (SWRLParseException e) {
            logger.warn("SWRL parse error: {}", e.getMessage());
            return new ValidationResult(false, 
                "Syntax error: " + e.getMessage(), 
                generateSuggestions(ruleText, e));
        } catch (Exception e) {
            logger.error("Validation error", e);
            return new ValidationResult(false, 
                "Validation error: " + e.getMessage(), 
                Collections.emptyList());
        }
    }

    public SwrlRule createRule(String projectId, String ruleName, String ruleText, String comment, String category) {
        try {
            if (ruleRepository.existsByProjectIdAndRuleName(projectId, ruleName)) {
                throw new IllegalArgumentException("Rule with name '" + ruleName + "' already exists");
            }

            ValidationResult validation = validateRule(projectId, ruleText);
            if (!validation.isValid()) {
                throw new IllegalArgumentException(validation.getErrorMessage());
            }

            SwrlRule rule = new SwrlRule(projectId, ruleName, ruleText);
            rule.setComment(comment);
            rule.setCategory(category);
            rule = ruleRepository.save(rule);

            OWLOntology ontology = ontologyClient.fetchOntology(projectId);
            SWRLRuleEngine engine = getOrCreateEngine(projectId, ontology);
            engine.createSWRLRule(ruleName, ruleText);

            logger.info("Created SWRL rule: {} for project: {}", ruleName, projectId);
            return rule;

        } catch (Exception e) {
            logger.error("Failed to create rule", e);
            throw new RuntimeException("Failed to create rule: " + e.getMessage());
        }
    }

    @CacheEvict(value = "executionResults", key = "#projectId")
    public ExecutionResult executeRules(String projectId) {
        try {
            long startTime = System.currentTimeMillis();

            OWLOntology ontology = ontologyClient.fetchOntology(projectId);
            SWRLRuleEngine engine = getOrCreateEngine(projectId, ontology);

            List<SwrlRule> enabledRules = ruleRepository.findByProjectIdAndEnabled(projectId, true);
            logger.info("Executing {} enabled rules for project: {}", enabledRules.size(), projectId);

            engine.getSWRLRules().forEach(rule -> engine.deleteSWRLRule(rule.getRuleName()));

            for (SwrlRule rule : enabledRules) {
                try {
                    engine.createSWRLRule(rule.getRuleName(), rule.getRuleText());
                } catch (SWRLParseException e) {
                    logger.error("Failed to add rule {}: {}", rule.getRuleName(), e.getMessage());
                }
            }

            engine.infer();

            Set<OWLAxiom> inferredAxioms = engine.getInferredOWLAxioms();
            long executionTime = System.currentTimeMillis() - startTime;

            logger.info("Executed {} rules in {}ms, inferred {} axioms", 
                       enabledRules.size(), executionTime, inferredAxioms.size());

            List<InferredAxiom> formattedAxioms = inferredAxioms.stream()
                .limit(1000)
                .map(this::formatInferredAxiom)
                .collect(Collectors.toList());

            return new ExecutionResult(
                true,
                executionTime,
                inferredAxioms.size(),
                formattedAxioms,
                null
            );

        } catch (SWRLBuiltInException e) {
            logger.error("SWRL built-in error", e);
            return new ExecutionResult(false, 0, 0, Collections.emptyList(),
                "Built-in function error: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Execution error", e);
            return new ExecutionResult(false, 0, 0, Collections.emptyList(),
                "Execution error: " + e.getMessage());
        }
    }

    public List<SwrlRule> getRules(String projectId) {
        return ruleRepository.findByProjectId(projectId);
    }

    public SwrlRule updateRule(String ruleId, String ruleText, String comment, Boolean enabled, String category) {
        SwrlRule rule = ruleRepository.findById(ruleId)
            .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + ruleId));

        if (ruleText != null && !ruleText.equals(rule.getRuleText())) {
            ValidationResult validation = validateRule(rule.getProjectId(), ruleText);
            if (!validation.isValid()) {
                throw new IllegalArgumentException(validation.getErrorMessage());
            }
            rule.setRuleText(ruleText);
        }

        if (comment != null) rule.setComment(comment);
        if (enabled != null) rule.setEnabled(enabled);
        if (category != null) rule.setCategory(category);

        rule.setUpdatedAt(LocalDateTime.now());
        return ruleRepository.save(rule);
    }

    public void deleteRule(String ruleId) {
        SwrlRule rule = ruleRepository.findById(ruleId)
            .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + ruleId));

        try {
            OWLOntology ontology = ontologyClient.fetchOntology(rule.getProjectId());
            SWRLRuleEngine engine = getOrCreateEngine(rule.getProjectId(), ontology);
            engine.deleteSWRLRule(rule.getRuleName());
        } catch (Exception e) {
            logger.warn("Failed to remove rule from engine: {}", e.getMessage());
        }

        ruleRepository.delete(rule);
        logger.info("Deleted rule: {}", ruleId);
    }

    private SWRLRuleEngine getOrCreateEngine(String projectId, OWLOntology ontology) {
        return engineCache.computeIfAbsent(projectId,
            id -> SWRLAPIFactory.createSWRLRuleEngine(ontology));
    }

    private InferredAxiom formatInferredAxiom(OWLAxiom axiom) {
        String axiomType = axiom.getAxiomType().getName();
        String description = axiom.toString();
        String readable = formatAxiomReadable(axiom);
        
        return new InferredAxiom(axiomType, description, readable);
    }

    private String formatAxiomReadable(OWLAxiom axiom) {
        return axiom.toString()
            .replace("http://www.w3.org/2002/07/owl#", "owl:")
            .replace("http://purl.obolibrary.org/obo/", "");
    }

    private List<String> generateSuggestions(String ruleText, SWRLParseException e) {
        List<String> suggestions = new ArrayList<>();
        
        if (ruleText.contains("->") && !ruleText.contains("^")) {
            suggestions.add("Did you forget to use ^ (AND) between atoms?");
        }
        
        if (!ruleText.contains("(?")) {
            suggestions.add("Variables should start with ? (e.g., ?person)");
        }
        
        return suggestions;
    }

    @CacheEvict(value = {"ruleEngines", "ontologies"}, key = "#projectId")
    public void clearCache(String projectId) {
        engineCache.remove(projectId);
        logger.info("Cleared cache for project: {}", projectId);
    }
}