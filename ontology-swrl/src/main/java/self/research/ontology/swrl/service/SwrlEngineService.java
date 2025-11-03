package self.research.ontology.swrl.service;

import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.swrlapi.factory.SWRLAPIFactory;
import org.swrlapi.core.SWRLRuleEngine;
import org.swrlapi.exceptions.SWRLBuiltInException;
import org.swrlapi.parser.SWRLParseException;
import self.research.ontology.swrl.model.*;
import self.research.ontology.swrl.dto.*;
import self.research.ontology.swrl.repository.SwrlRuleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import self.research.ontology.swrl.dto.CreateRuleRequest;
import self.research.ontology.swrl.dto.ImportResult;
import self.research.ontology.swrl.dto.RuleStatistics;

/**
 * IMPROVED VERSION - Fixed thread safety and memory leaks
 */
@Service
public class SwrlEngineService {

    private static final Logger logger = LoggerFactory.getLogger(SwrlEngineService.class);

    @Autowired
    private OntologyClientService ontologyClient;

    @Autowired
    private SwrlRuleRepository ruleRepository;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @Value("${swrl.max-inferred-axioms:1000}")
    private int maxInferredAxioms;

    @Value("${swrl.execution-timeout-seconds:30}")
    private int executionTimeoutSeconds;

    @Value("${swrl.engine-cache-ttl-hours:24}")
    private int engineCacheTtlHours;

    // ✅ FIXED: Thread-safe cache
    private final Map<String, SWRLRuleEngine> engineCache = new ConcurrentHashMap<>();
    
    // ✅ FIXED: Track last access for cleanup
    private final Map<String, Long> engineLastAccess = new ConcurrentHashMap<>();

    // ✅ FIXED: Executor for timeouts
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    /**
     * ✅ NEW: Cleanup stale engines periodically
     */
    @Scheduled(fixedDelayString = "${swrl.cache-cleanup-interval-ms:3600000}") // Default: 1 hour
    public void cleanupStaleEngines() {
        long cutoffTime = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(engineCacheTtlHours);
        
        int removedCount = 0;
        Iterator<Map.Entry<String, Long>> iterator = engineLastAccess.entrySet().iterator();
        
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (entry.getValue() < cutoffTime) {
                String projectId = entry.getKey();
                engineCache.remove(projectId);
                iterator.remove();
                removedCount++;
            }
        }
        
        if (removedCount > 0) {
            logger.info("Cleaned up {} stale SWRL engines. Active engines: {}", 
                       removedCount, engineCache.size());
        }
    }

    public ValidationResult validateRule(String projectId, String ruleText) {
        Timer.Sample sample = meterRegistry != null ? Timer.start(meterRegistry) : null;
        
        try {
            OWLOntology ontology = ontologyClient.fetchOntology(projectId);
            SWRLRuleEngine engine = getOrCreateEngine(projectId, ontology);
            
            String tempName = "temp_validation_" + System.currentTimeMillis();
            engine.createSWRLRule(tempName, ruleText);
            engine.deleteSWRLRule(tempName);
            
            logger.info("Rule validation successful for project: {}", projectId);
            
            if (sample != null) {
                sample.stop(Timer.builder("swrl.validation")
                    .tag("projectId", projectId)
                    .tag("status", "success")
                    .register(meterRegistry));
            }
            
            return new ValidationResult(true, null, Collections.emptyList());
            
        } catch (SWRLParseException e) {
            logger.warn("SWRL parse error for project {}: {}", projectId, e.getMessage());
            
            if (meterRegistry != null) {
                meterRegistry.counter("swrl.validation.errors",
                    "projectId", projectId,
                    "type", "parse").increment();
            }
            
            return new ValidationResult(false, 
                "Syntax error: " + e.getMessage(), 
                generateEnhancedSuggestions(ruleText, e));
                
        } catch (Exception e) {
            logger.error("Validation error for project {}", projectId, e);
            
            if (meterRegistry != null) {
                meterRegistry.counter("swrl.validation.errors",
                    "projectId", projectId,
                    "type", "system").increment();
            }
            
            return new ValidationResult(false, 
                "Validation error: " + e.getMessage(), 
                Collections.emptyList());
        }
    }

    public SwrlRule createRule(String projectId, String ruleName, String ruleText, 
                              String comment, String category) {
        try {
            // Check for duplicate rule name
            if (ruleRepository.existsByProjectIdAndRuleName(projectId, ruleName)) {
                throw new IllegalArgumentException("Rule with name '" + ruleName + "' already exists");
            }

            // Validate rule syntax
            ValidationResult validation = validateRule(projectId, ruleText);
            if (!validation.isValid()) {
                throw new IllegalArgumentException(validation.getErrorMessage());
            }

            // Create and save rule
            SwrlRule rule = new SwrlRule(projectId, ruleName, ruleText);
            rule.setComment(comment);
            rule.setCategory(category);
            rule = ruleRepository.save(rule);

            // Add to engine
            OWLOntology ontology = ontologyClient.fetchOntology(projectId);
            SWRLRuleEngine engine = getOrCreateEngine(projectId, ontology);
            engine.createSWRLRule(ruleName, ruleText);

            logger.info("Created SWRL rule: {} for project: {}", ruleName, projectId);
            
            if (meterRegistry != null) {
                meterRegistry.counter("swrl.rules.created", 
                    "projectId", projectId).increment();
            }
            
            return rule;

        } catch (Exception e) {
            logger.error("Failed to create rule {} for project {}", ruleName, projectId, e);
            throw new RuntimeException("Failed to create rule: " + e.getMessage(), e);
        }
    }

    @CacheEvict(value = "executionResults", key = "#projectId")
    public ExecutionResult executeRules(String projectId) {
        Timer.Sample sample = meterRegistry != null ? Timer.start(meterRegistry) : null;
        long startTime = System.currentTimeMillis();

        try {
            OWLOntology ontology = ontologyClient.fetchOntology(projectId);
            SWRLRuleEngine engine = getOrCreateEngine(projectId, ontology);

            List<SwrlRule> enabledRules = ruleRepository.findByProjectIdAndEnabled(projectId, true);
            logger.info("Executing {} enabled rules for project: {}", enabledRules.size(), projectId);

            // Clear existing rules from engine
            engine.getSWRLRules().forEach(rule -> engine.deleteSWRLRule(rule.getRuleName()));

            // Add enabled rules
            for (SwrlRule rule : enabledRules) {
                try {
                    engine.createSWRLRule(rule.getRuleName(), rule.getRuleText());
                } catch (SWRLParseException e) {
                    logger.error("Failed to add rule {}: {}", rule.getRuleName(), e.getMessage());
                }
            }

            // ✅ FIXED: Execute with timeout
            Set<OWLAxiom> inferredAxioms = executeWithTimeout(engine, projectId);
            
            long executionTime = System.currentTimeMillis() - startTime;

            logger.info("Executed {} rules in {}ms, inferred {} axioms", 
                       enabledRules.size(), executionTime, inferredAxioms.size());

            // Update rule execution stats
            updateRuleExecutionStats(enabledRules, executionTime);

            List<InferredAxiom> formattedAxioms = inferredAxioms.stream()
                .limit(maxInferredAxioms)  // ✅ FIXED: Configurable limit
                .map(this::formatInferredAxiom)
                .collect(Collectors.toList());

            if (sample != null) {
                sample.stop(Timer.builder("swrl.execution")
                    .tag("projectId", projectId)
                    .tag("status", "success")
                    .register(meterRegistry));
            }
            
            if (meterRegistry != null) {
                meterRegistry.counter("swrl.axioms.inferred",
                    "projectId", projectId).increment(inferredAxioms.size());
            }

            return new ExecutionResult(
                true,
                executionTime,
                inferredAxioms.size(),
                formattedAxioms,
                null
            );

        } catch (TimeoutException e) {
            logger.error("Rule execution timed out for project: {}", projectId);
            
            if (meterRegistry != null) {
                meterRegistry.counter("swrl.execution.errors",
                    "projectId", projectId,
                    "type", "timeout").increment();
            }
            
            return new ExecutionResult(false, 0, 0, Collections.emptyList(),
                "Execution timed out after " + executionTimeoutSeconds + " seconds");
                
        } catch (SWRLBuiltInException e) {
            logger.error("SWRL built-in error for project {}", projectId, e);
            
            if (meterRegistry != null) {
                meterRegistry.counter("swrl.execution.errors",
                    "projectId", projectId,
                    "type", "builtin").increment();
            }
            
            return new ExecutionResult(false, 0, 0, Collections.emptyList(),
                "Built-in function error: " + e.getMessage());
                
        } catch (Exception e) {
            logger.error("Execution error for project {}", projectId, e);
            
            if (meterRegistry != null) {
                meterRegistry.counter("swrl.execution.errors",
                    "projectId", projectId,
                    "type", "system").increment();
            }
            
            return new ExecutionResult(false, 0, 0, Collections.emptyList(),
                "Execution error: " + e.getMessage());
        }
    }

    /**
     * ✅ NEW: Execute inference with timeout protection
     */
    private Set<OWLAxiom> executeWithTimeout(SWRLRuleEngine engine, String projectId) 
            throws TimeoutException, InterruptedException, ExecutionException {
        
        Future<Set<OWLAxiom>> future = executorService.submit(() -> {
            engine.infer();
            return engine.getInferredOWLAxioms();
        });

        try {
            return future.get(executionTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            logger.error("Rule execution timed out for project: {}", projectId);
            throw e;
        }
    }

    /**
     * ✅ NEW: Update execution statistics for rules
     */
    private void updateRuleExecutionStats(List<SwrlRule> rules, long executionTime) {
        for (SwrlRule rule : rules) {
            rule.setExecutionCount(rule.getExecutionCount() + 1);
            rule.setLastExecutionTime(executionTime);
            ruleRepository.save(rule);
        }
    }

    public List<SwrlRule> getRules(String projectId) {
        return ruleRepository.findByProjectId(projectId);
    }

    /**
     * ✅ NEW: Get single rule (was missing!)
     */
    public SwrlRule getRule(String projectId, String ruleId) {
        return ruleRepository.findByIdAndProjectId(ruleId, projectId)
            .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + ruleId));
    }

    public SwrlRule updateRule(String ruleId, String ruleText, String comment, 
                               Boolean enabled, String category) {
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
        
        SwrlRule updated = ruleRepository.save(rule);
        
        if (meterRegistry != null) {
            meterRegistry.counter("swrl.rules.updated", 
                "projectId", rule.getProjectId()).increment();
        }
        
        return updated;
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
        
        if (meterRegistry != null) {
            meterRegistry.counter("swrl.rules.deleted", 
                "projectId", rule.getProjectId()).increment();
        }
        
        logger.info("Deleted rule: {}", ruleId);
    }

    /**
     * ✅ FIXED: Track access time for cleanup
     */
    private SWRLRuleEngine getOrCreateEngine(String projectId, OWLOntology ontology) {
        engineLastAccess.put(projectId, System.currentTimeMillis());
        
        return engineCache.computeIfAbsent(projectId, id -> {
            logger.info("Creating new SWRL engine for project: {}", projectId);
            return SWRLAPIFactory.createSWRLRuleEngine(ontology);
        });
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

    /**
     * ✅ IMPROVED: Enhanced suggestions with more context
     */
    private List<String> generateEnhancedSuggestions(String ruleText, SWRLParseException e) {
        List<String> suggestions = new ArrayList<>();
        String errorMsg = e.getMessage().toLowerCase();
        
        // Missing conjunction
        if (ruleText.contains("->") && !ruleText.contains("^")) {
            suggestions.add("Missing ^ (AND) between atoms in body or head");
            suggestions.add("Example: Person(?p) ^ hasAge(?p, ?age) -> Adult(?p)");
        }
        
        // Missing variables
        if (!ruleText.contains("?")) {
            suggestions.add("Variables must start with ? (e.g., ?person, ?age)");
        }
        
        // Missing implication
        if (!ruleText.contains("->")) {
            suggestions.add("SWRL rules require -> to separate body from head");
            suggestions.add("Format: Body -> Head (e.g., Person(?p) -> Human(?p))");
        }
        
        // Malformed atoms
        if (errorMsg.contains("unexpected") || errorMsg.contains("expecting")) {
            suggestions.add("Check for typos in class/property names");
            suggestions.add("Ensure all predicates are defined in your ontology");
            suggestions.add("Verify proper capitalization (classes start with uppercase)");
        }
        
        // Built-in errors
        if (errorMsg.contains("builtin")) {
            suggestions.add("Check SWRL built-in syntax");
            suggestions.add("Example: swrlb:greaterThan(?age, 18)");
            suggestions.add("Common built-ins: swrlb:equal, swrlb:lessThan, swrlb:add");
        }
        
        // Parentheses issues
        if (errorMsg.contains("parenthes") || errorMsg.contains("bracket")) {
            suggestions.add("Check that all parentheses are balanced");
            suggestions.add("Each atom should be: Predicate(arg1, arg2, ...)");
        }
        
        // Parse error location
        String parseError = e.getMessage();
        if (parseError.contains("line") || parseError.contains("column")) {
            suggestions.add("Syntax error location: " + parseError);
        }
        
        return suggestions;
    }

    @CacheEvict(value = {"ruleEngines", "ontologies", "executionResults"}, key = "#projectId")
    public void clearCache(String projectId) {
        engineCache.remove(projectId);
        engineLastAccess.remove(projectId);
        logger.info("Cleared cache for project: {}", projectId);
    }

    /**
     * ✅ NEW: Get cache statistics
     */
    public Map<String, Object> getCacheStats() {
        return Map.of(
            "activateEngines", engineCache.size(),
            "oldestEngineAge", getOldestEngineAge(),
            "memoryUsage", getApproximateMemoryUsage()
        );
    }

    private long getOldestEngineAge() {
        if (engineLastAccess.isEmpty()) return 0;
        long oldest = Collections.min(engineLastAccess.values());
        return System.currentTimeMillis() - oldest;
    }

    private String getApproximateMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        return usedMemory + " MB";
    }

    /**
     * Search rules by text
     */
    public Page<SwrlRule> searchRules(String projectId, String searchText, Pageable pageable) {
        return ruleRepository.findByProjectIdAndRuleNameContainingOrRuleTextContaining(
                projectId, searchText, searchText, pageable);
    }

    /**
     * Get rules by category with pagination
     */
    public Page<SwrlRule> getRulesByCategory(String projectId, String category, Pageable pageable) {
        return ruleRepository.findByProjectIdAndCategory(projectId, category, pageable);
    }

    /**
     * Get rules by enabled status with pagination
     */
    public Page<SwrlRule> getRulesByEnabled(String projectId, Boolean enabled, Pageable pageable) {
        return ruleRepository.findByProjectIdAndEnabled(projectId, enabled, pageable);
    }

    /**
     * Get all rules with pagination
     */
    public Page<SwrlRule> getRulesPaginated(String projectId, Pageable pageable) {
        return ruleRepository.findByProjectId(projectId, pageable);
    }

    /**
     * Test a single rule without saving
     */
    public ExecutionResult testSingleRule(String projectId, String ruleText) {
        Timer.Sample sample = meterRegistry != null ? Timer.start(meterRegistry) : null;
        long startTime = System.currentTimeMillis();

        try {
            // Validate syntax first
            ValidationResult validation = validateRule(projectId, ruleText);
            if (!validation.isValid()) {
                return new ExecutionResult(false, 0, 0, Collections.emptyList(),
                        validation.getErrorMessage());
            }

            OWLOntology ontology = ontologyClient.fetchOntology(projectId);
            SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);

            // Add and execute the test rule
            String testRuleName = "test_" + System.currentTimeMillis();
            engine.createSWRLRule(testRuleName, ruleText);

            Set<OWLAxiom> inferredAxioms = executeWithTimeout(engine, projectId);
            long executionTime = System.currentTimeMillis() - startTime;

            List<InferredAxiom> formattedAxioms = inferredAxioms.stream()
                    .limit(maxInferredAxioms)
                    .map(this::formatInferredAxiom)
                    .collect(Collectors.toList());

            if (sample != null) {
                sample.stop(Timer.builder("swrl.test")
                        .tag("projectId", projectId)
                        .register(meterRegistry));
            }

            return new ExecutionResult(true, executionTime, inferredAxioms.size(),
                    formattedAxioms, null);

        } catch (Exception e) {
            logger.error("Test execution failed for project {}", projectId, e);
            return new ExecutionResult(false, 0, 0, Collections.emptyList(),
                    "Test failed: " + e.getMessage());
        }
    }

    /**
     * Create multiple rules at once
     */
    public List<SwrlRule> createRulesBatch(String projectId, List<CreateRuleRequest> requests) {
        List<SwrlRule> createdRules = new ArrayList<>();

        for (CreateRuleRequest request : requests) {
            try {
                SwrlRule rule = createRule(projectId, request.getRuleName(),
                        request.getRuleText(), request.getComment(), request.getCategory());
                createdRules.add(rule);
            } catch (Exception e) {
                logger.error("Failed to create rule in batch: {}", request.getRuleName(), e);
            }
        }

        logger.info("Batch created {} of {} rules for project {}",
                createdRules.size(), requests.size(), projectId);

        return createdRules;
    }

    /**
     * Delete multiple rules at once
     */
    public void deleteRulesBatch(List<String> ruleIds) {
        for (String ruleId : ruleIds) {
            try {
                deleteRule(ruleId);
            } catch (Exception e) {
                logger.error("Failed to delete rule in batch: {}", ruleId, e);
            }
        }

        logger.info("Batch deleted {} rules", ruleIds.size());
    }

    /**
     * Import rules from list
     */
    public ImportResult importRules(String projectId, List<CreateRuleRequest> requests) {
        int successCount = 0;
        int failureCount = 0;
        List<String> errors = new ArrayList<>();

        for (CreateRuleRequest request : requests) {
            try {
                createRule(projectId, request.getRuleName(), request.getRuleText(),
                        request.getComment(), request.getCategory());
                successCount++;
            } catch (Exception e) {
                failureCount++;
                errors.add("Failed to import rule '" + request.getRuleName() + "': " + e.getMessage());
                logger.error("Import failed for rule: {}", request.getRuleName(), e);
            }
        }

        logger.info("Import completed for project {}: {} success, {} failures",
                projectId, successCount, failureCount);

        ImportResult result = new ImportResult();
        result.setSuccessCount(successCount);
        result.setFailureCount(failureCount);
        result.setErrors(errors);
        return result;
    }

    /**
     * Get statistics about rules
     */
    public RuleStatistics getRuleStatistics(String projectId) {
        List<SwrlRule> allRules = ruleRepository.findByProjectId(projectId);

        long totalRules = allRules.size();
        long enabledRules = allRules.stream().filter(SwrlRule::isEnabled).count();
        long disabledRules = totalRules - enabledRules;
        long totalExecutions = allRules.stream()
                .mapToLong(SwrlRule::getExecutionCount)
                .sum();

        long averageExecutionTime = (long) allRules.stream()
                .filter(r -> r.getLastExecutionTime() != null)
                .mapToLong(SwrlRule::getLastExecutionTime)
                .average()
                .orElse(0);

        String mostUsedCategory = allRules.stream()
                .filter(r -> r.getCategory() != null)
                .collect(Collectors.groupingBy(SwrlRule::getCategory, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        RuleStatistics stats = new RuleStatistics();
        stats.setTotalRules(totalRules);
        stats.setEnabledRules(enabledRules);
        stats.setDisabledRules(disabledRules);
        stats.setTotalExecutions(totalExecutions);
        stats.setAverageExecutionTime(averageExecutionTime);
        stats.setMostUsedCategory(mostUsedCategory);

        return stats;
    }

    /**
     * Duplicate an existing rule
     */
    public SwrlRule duplicateRule(String ruleId, String newRuleName) {
        SwrlRule original = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + ruleId));

        if (ruleRepository.existsByProjectIdAndRuleName(original.getProjectId(), newRuleName)) {
            throw new IllegalArgumentException("Rule with name '" + newRuleName + "' already exists");
        }

        SwrlRule duplicate = new SwrlRule(original.getProjectId(), newRuleName, original.getRuleText());
        duplicate.setComment(original.getComment());
        duplicate.setCategory(original.getCategory());
        duplicate.setEnabled(original.isEnabled());

        duplicate = ruleRepository.save(duplicate);

        logger.info("Duplicated rule {} to {} for project {}",
                ruleId, newRuleName, original.getProjectId());

        return duplicate;
    }
}