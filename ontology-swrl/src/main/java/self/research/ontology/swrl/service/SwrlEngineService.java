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
    private static final Logger perfLog = LoggerFactory.getLogger("PERFORMANCE");
    private static final Logger engineLog = LoggerFactory.getLogger("SWRL_ENGINE");

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

    // Stable namespace-to-prefix mapping per project (ensures consistent prefix names across calls)
    private final Map<String, Map<String, String>> projectNamespacePrefixes = new ConcurrentHashMap<>();

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
        long startTime = System.currentTimeMillis();
        engineLog.info("[VALIDATE] Starting validation project={} ruleLength={}", projectId, ruleText.length());
        Timer.Sample sample = meterRegistry != null ? Timer.start(meterRegistry) : null;
        
        try {
            long fetchStart = System.currentTimeMillis();
            OWLOntology ontology = ontologyClient.fetchOntology(projectId);
            long fetchDuration = System.currentTimeMillis() - fetchStart;
            engineLog.info("[VALIDATE] Ontology fetched in {}ms project={}", fetchDuration, projectId);

            // Register namespace prefixes for name resolution only (don't touch the shared engine cache)
            ensureNamespacePrefixes(projectId, ontology);

            // Use a FRESH isolated engine for validation so that infer() only runs the one
            // rule being validated — not all previously saved/enabled rules in the cached engine.
            long engineStart = System.currentTimeMillis();
            SWRLRuleEngine validationEngine = SWRLAPIFactory.createSWRLRuleEngine(ontology);
            long engineDuration = System.currentTimeMillis() - engineStart;
            engineLog.info("[VALIDATE] Fresh validation engine created in {}ms project={}", engineDuration, projectId);

            String tempName = "temp_validation_" + System.currentTimeMillis();
            String resolvedText = resolveEntityNames(ruleText, ontology);

            try {
                validationEngine.createSWRLRule(tempName, resolvedText);
            } catch (SWRLParseException parseEx) {
                // Re-throw so the outer catch block handles it with enhanced suggestions
                throw parseEx;
            } catch (Exception createEx) {
                // createSWRLRule throws a non-parse exception when a class/property name in
                // the rule does not exist in the ontology (e.g. "Invalid SWRL atom predicate").
                String msg = createEx.getMessage() != null ? createEx.getMessage() : createEx.getClass().getSimpleName();
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("Invalid SWRL atom predicate '([^']+)'").matcher(msg);
                if (m.find()) {
                    String missing = m.group(1);
                    return new ValidationResult(false,
                        "Unknown class or property '" + missing + "'",
                        List.of(
                            "'" + missing + "' does not exist in this ontology.",
                            "To infer new class membership: create the class '" + missing + "' first using the Entities panel, then use it in this rule.",
                            "To use an existing class: check the spelling against the Entities panel."
                        ));
                }
                throw createEx;
            }

            // Semantic validation: run inference on the isolated engine to catch unbound
            // argument errors in built-ins. Only the one temp rule runs here.
            try {
                validationEngine.infer();
            } catch (Exception inferEx) {
                String errorMsg = inferEx.getMessage() != null ? inferEx.getMessage() : inferEx.getClass().getSimpleName();

                if (inferEx instanceof SWRLBuiltInException
                        || errorMsg.contains("built-in")
                        || errorMsg.contains("do not support argument binding")) {

                    logger.warn("SWRL built-in validation error for project {}: {}", projectId, errorMsg);

                    if (meterRegistry != null) {
                        meterRegistry.counter("swrl.validation.errors",
                            "projectId", projectId, "type", "builtin").increment();
                    }

                    List<String> suggestions = new ArrayList<>();
                    if (errorMsg.contains("do not support argument binding")) {
                        suggestions.add("Comparison built-ins require all arguments to be already bound by a class or property atom.");
                        suggestions.add("Example: Person(?p) ^ hasAge(?p, ?age) ^ swrlb:greaterThan(?age, 18) -> Adult(?p)");
                    }
                    if (errorMsg.contains("PlainLiteral") || errorMsg.contains("xsd:string")) {
                        suggestions.add("The property's range is declared as a string type. Change it to xsd:integer or xsd:decimal for numeric comparisons.");
                    }

                    return new ValidationResult(false, "Built-in function error: " + errorMsg, suggestions);
                }

                throw inferEx;
            }
            
            logger.info("Rule validation successful for project: {}", projectId);
            long totalDuration = System.currentTimeMillis() - startTime;
            perfLog.info("[PERF] SWRL_VALIDATE project={} status=success duration={}ms", projectId, totalDuration);
            engineLog.info("[VALIDATE] Completed successfully in {}ms project={}", totalDuration, projectId);
            
            if (sample != null) {
                sample.stop(Timer.builder("swrl.validation")
                    .tag("projectId", projectId)
                    .tag("status", "success")
                    .register(meterRegistry));
            }
            
            return new ValidationResult(true, null, Collections.emptyList());
            
        } catch (SWRLParseException e) {
            long totalDuration = System.currentTimeMillis() - startTime;
            logger.warn("SWRL parse error for project {}: {}", projectId, e.getMessage());
            perfLog.info("[PERF] SWRL_VALIDATE project={} status=parse_error duration={}ms", projectId, totalDuration);
            engineLog.warn("[VALIDATE] Parse error in {}ms project={}: {}", totalDuration, projectId, e.getMessage());
            
            if (meterRegistry != null) {
                meterRegistry.counter("swrl.validation.errors",
                    "projectId", projectId,
                    "type", "parse").increment();
            }
            
            return new ValidationResult(false, 
                "Syntax error: " + e.getMessage(), 
                generateEnhancedSuggestions(ruleText, e));
                
        } catch (Exception e) {
            long totalDuration = System.currentTimeMillis() - startTime;
            logger.error("Validation error for project {}", projectId, e);
            perfLog.info("[PERF] SWRL_VALIDATE project={} status=error duration={}ms error={}", projectId, totalDuration, e.getMessage());
            engineLog.error("[VALIDATE] Error in {}ms project={}: {}", totalDuration, projectId, e.getMessage());
            
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
        long startTime = System.currentTimeMillis();
        engineLog.info("[CREATE_RULE] Starting project={} ruleName={}", projectId, ruleName);
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
            if (ensureNamespacePrefixes(projectId, ontology)) {
                engineCache.remove(projectId);
            }
            SWRLRuleEngine engine = getOrCreateEngine(projectId, ontology);
            String resolvedText = resolveEntityNames(ruleText, ontology);
            engine.createSWRLRule(ruleName, resolvedText);

            logger.info("Created SWRL rule: {} for project: {}", ruleName, projectId);
            
            if (meterRegistry != null) {
                meterRegistry.counter("swrl.rules.created", 
                    "projectId", projectId).increment();
            }
            
            long totalDuration = System.currentTimeMillis() - startTime;
            perfLog.info("[PERF] SWRL_CREATE_RULE project={} rule={} duration={}ms", projectId, ruleName, totalDuration);
            engineLog.info("[CREATE_RULE] Completed in {}ms project={} rule={}", totalDuration, projectId, ruleName);
            return rule;

        } catch (Exception e) {
            long totalDuration = System.currentTimeMillis() - startTime;
            logger.error("Failed to create rule {} for project {} after {}ms", ruleName, projectId, totalDuration, e);
            perfLog.info("[PERF] SWRL_CREATE_RULE project={} rule={} status=error duration={}ms", projectId, ruleName, totalDuration);
            throw new RuntimeException("Failed to create rule: " + e.getMessage(), e);
        }
    }

    @CacheEvict(value = "executionResults", key = "#projectId")
    public ExecutionResult executeRules(String projectId) {
        Timer.Sample sample = meterRegistry != null ? Timer.start(meterRegistry) : null;
        long startTime = System.currentTimeMillis();

        try {
            long fetchStart = System.currentTimeMillis();
            OWLOntology ontology = ontologyClient.fetchOntology(projectId);
            long fetchDuration = System.currentTimeMillis() - fetchStart;
            engineLog.info("[EXECUTE] Ontology fetched in {}ms project={}", fetchDuration, projectId);

            ensureNamespacePrefixes(projectId, ontology);
            long engineStart = System.currentTimeMillis();
            // Always build a fresh engine from the just-fetched ontology rather than
            // reusing one cached by projectId — a cached engine wraps whatever
            // ontology snapshot existed when it was first created, and
            // ensureNamespacePrefixes() only busts that cache on a brand-new
            // namespace, not on new individuals/assertions added under a namespace
            // the engine already knew about. A stale engine here silently reasons
            // over stale data with no error at all — same class of bug as the
            // ontology-fetch cache in OntologyClientService.
            SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);
            long engineDuration = System.currentTimeMillis() - engineStart;
            engineLog.info("[EXECUTE] Fresh engine created in {}ms project={}", engineDuration, projectId);

            List<SwrlRule> enabledRules = ruleRepository.findByProjectIdAndEnabled(projectId, true);
            logger.info("Executing {} enabled rules for project: {}", enabledRules.size(), projectId);
            engineLog.info("[EXECUTE] {} rules to execute project={}", enabledRules.size(), projectId);

            // Clear existing rules from engine
            engine.getSWRLRules().forEach(rule -> engine.deleteSWRLRule(rule.getRuleName()));

            // Add enabled rules
            for (SwrlRule rule : enabledRules) {
                try {
                    String resolved = resolveEntityNames(rule.getRuleText(), ontology);
                    engine.createSWRLRule(rule.getRuleName(), resolved);
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

            // Extract executed rule names
            List<String> executedRuleNames = enabledRules.stream()
                .map(SwrlRule::getRuleName)
                .collect(Collectors.toList());

            ExecutionResult result = new ExecutionResult(
                true,
                executionTime,
                inferredAxioms.size(),
                formattedAxioms,
                null
            );
            result.setExecutedRuleNames(executedRuleNames);
            result.setExecutionMode("all");
            perfLog.info("[PERF] SWRL_EXECUTE project={} rules={} inferred={} duration={}ms status=success",
                    projectId, enabledRules.size(), inferredAxioms.size(), executionTime);
            engineLog.info("[EXECUTE] Completed: {} rules, {} inferred axioms in {}ms project={}",
                    enabledRules.size(), inferredAxioms.size(), executionTime, projectId);
            return result;

        } catch (TimeoutException e) {
            long totalDuration = System.currentTimeMillis() - startTime;
            logger.error("Rule execution timed out for project: {} after {}ms", projectId, totalDuration);
            perfLog.info("[PERF] SWRL_EXECUTE project={} status=timeout duration={}ms", projectId, totalDuration);
            engineLog.error("[EXECUTE] Timeout after {}ms project={}", totalDuration, projectId);
            
            if (meterRegistry != null) {
                meterRegistry.counter("swrl.execution.errors",
                    "projectId", projectId,
                    "type", "timeout").increment();
            }
            
            return new ExecutionResult(false, 0, 0, Collections.emptyList(),
                "Execution timed out after " + executionTimeoutSeconds + " seconds");
                
        } catch (SWRLBuiltInException e) {
            long totalDuration = System.currentTimeMillis() - startTime;
            logger.error("SWRL built-in error for project {} after {}ms", projectId, totalDuration, e);
            perfLog.info("[PERF] SWRL_EXECUTE project={} status=builtin_error duration={}ms", projectId, totalDuration);
            engineLog.error("[EXECUTE] Built-in error in {}ms project={}: {}", totalDuration, projectId, e.getMessage());
            
            if (meterRegistry != null) {
                meterRegistry.counter("swrl.execution.errors",
                    "projectId", projectId,
                    "type", "builtin").increment();
            }
            
            return new ExecutionResult(false, 0, 0, Collections.emptyList(),
                "Built-in function error: " + e.getMessage());
                
        } catch (Exception e) {
            long totalDuration = System.currentTimeMillis() - startTime;
            logger.error("Execution error for project {} after {}ms", projectId, totalDuration, e);
            perfLog.info("[PERF] SWRL_EXECUTE project={} status=error duration={}ms error={}", projectId, totalDuration, e.getMessage());
            engineLog.error("[EXECUTE] Error in {}ms project={}: {}", totalDuration, projectId, e.getMessage());
            
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
     * ✅ NEW: Execute selected rules by IDs
     */
    @CacheEvict(value = "executionResults", key = "#projectId")
    public ExecutionResult executeSelectedRules(String projectId, List<String> ruleIds) {
        Timer.Sample sample = meterRegistry != null ? Timer.start(meterRegistry) : null;
        long startTime = System.currentTimeMillis();

        try {
            OWLOntology ontology = ontologyClient.fetchOntology(projectId);
            ensureNamespacePrefixes(projectId, ontology);
            // Fresh engine per call — see executeRules() for why the cached
            // getOrCreateEngine() path is unsafe here.
            SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);

            // Get the selected rules
            List<SwrlRule> selectedRules = ruleRepository.findAllById(ruleIds);
            if (selectedRules.isEmpty()) {
                return new ExecutionResult(false, 0, 0, Collections.emptyList(),
                    "No valid rules found for the given IDs");
            }
            
            logger.info("Executing {} selected rules for project: {}", selectedRules.size(), projectId);

            // Clear existing rules from engine
            engine.getSWRLRules().forEach(rule -> engine.deleteSWRLRule(rule.getRuleName()));

            // Add only selected rules
            for (SwrlRule rule : selectedRules) {
                try {
                    String resolved = resolveEntityNames(rule.getRuleText(), ontology);
                    engine.createSWRLRule(rule.getRuleName(), resolved);
                } catch (SWRLParseException e) {
                    logger.error("Failed to add rule {}: {}", rule.getRuleName(), e.getMessage());
                }
            }

            // Execute with timeout
            Set<OWLAxiom> inferredAxioms = executeWithTimeout(engine, projectId);
            
            long executionTime = System.currentTimeMillis() - startTime;

            logger.info("Executed {} selected rules in {}ms, inferred {} axioms", 
                       selectedRules.size(), executionTime, inferredAxioms.size());

            // Update rule execution stats
            updateRuleExecutionStats(selectedRules, executionTime);

            List<InferredAxiom> formattedAxioms = inferredAxioms.stream()
                .limit(maxInferredAxioms)
                .map(this::formatInferredAxiom)
                .collect(Collectors.toList());

            if (sample != null) {
                sample.stop(Timer.builder("swrl.execution.selected")
                    .tag("projectId", projectId)
                    .tag("status", "success")
                    .register(meterRegistry));
            }

            // Extract executed rule names
            List<String> executedRuleNames = selectedRules.stream()
                .map(SwrlRule::getRuleName)
                .collect(Collectors.toList());

            ExecutionResult result = new ExecutionResult(
                true,
                executionTime,
                inferredAxioms.size(),
                formattedAxioms,
                null
            );
            result.setExecutedRuleNames(executedRuleNames);
            result.setExecutionMode("selected");
            return result;

        } catch (TimeoutException e) {
            logger.error("Selected rules execution timed out for project: {}", projectId);
            return new ExecutionResult(false, 0, 0, Collections.emptyList(),
                "Execution timed out after " + executionTimeoutSeconds + " seconds");
                
        } catch (Exception e) {
            logger.error("Selected rules execution error for project {}", projectId, e);
            return new ExecutionResult(false, 0, 0, Collections.emptyList(),
                "Execution error: " + e.getMessage());
        }
    }

    /**
     * Execute inference with timeout protection.
     *
     * SWRLAPI's naming is misleading here: getInferredOWLAxioms() is where regular
     * SWRL rule conclusions actually land (ClassAssertion/ObjectPropertyAssertion/etc.
     * produced by a fired rule head), mixed in with the engine's OWL 2 RL closure —
     * both go through the same rule-engine bridge. getInjectedOWLAxioms() is a much
     * narrower bucket: it only holds axioms created by SWRL *built-in* invocations
     * (e.g. swrlb:add binding a new computed value), not regular rule firings. A
     * rule with no built-ins — the common case — will always report 0 injected
     * axioms even when it fired correctly; that is not an error. Both sets are
     * combined below since either can legitimately contain rule output.
     */
    private Set<OWLAxiom> executeWithTimeout(SWRLRuleEngine engine, String projectId)
            throws TimeoutException, InterruptedException, ExecutionException {

        Future<Set<OWLAxiom>> future = executorService.submit(() -> {
            engine.infer();

            Set<OWLAxiom> inferredAxioms = engine.getInferredOWLAxioms();
            Set<OWLAxiom> injectedAxioms = engine.getInjectedOWLAxioms();

            Set<OWLAxiom> allAxioms = new java.util.HashSet<>(inferredAxioms);
            allAxioms.addAll(injectedAxioms);

            Map<String, Long> inferredTypeCounts = inferredAxioms.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    ax -> ax.getAxiomType().getName(),
                    java.util.stream.Collectors.counting()
                ));
            logger.debug("Inferred axiom types for project {}: {}", projectId, inferredTypeCounts);

            long classAssertionCount = allAxioms.stream()
                .filter(ax -> ax.getAxiomType().getName().equals("ClassAssertion"))
                .count();
            logger.info("ClassAssertion axioms in result for project {}: {}", projectId, classAssertionCount);

            logger.info("Total axioms returned: {} (from rule engine: {}, from SWRL built-ins: {})",
                allAxioms.size(), inferredAxioms.size(), injectedAxioms.size());

            return allAxioms;
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
     * Register all non-default entity namespaces as prefixes on the ontology document format.
     * SWRLAPI reads prefix mappings from the format when creating its internal resolver,
     * enabling it to resolve prefixed names like ns1:BFO_0000015.
     * Returns true if new prefixes were registered (engine cache should be evicted).
     */
    private boolean ensureNamespacePrefixes(String projectId, OWLOntology ontology) {
        String defaultNs = null;
        try {
            com.google.common.base.Optional<IRI> ontIRI = ontology.getOntologyID().getOntologyIRI();
            if (ontIRI.isPresent()) {
                defaultNs = ontIRI.get().toString();
                if (!defaultNs.endsWith("#") && !defaultNs.endsWith("/")) {
                    defaultNs = defaultNs + "#";
                }
            }
        } catch (Exception e) { /* ignore */ }

        OWLDocumentFormat format = ontology.getOWLOntologyManager().getOntologyFormat(ontology);
        if (!(format instanceof org.semanticweb.owlapi.formats.PrefixDocumentFormat)) return false;

        // Get or create stable prefix map for this project
        Map<String, String> prefixMap = projectNamespacePrefixes
                .computeIfAbsent(projectId, k -> new ConcurrentHashMap<>());

        // Collect all non-default, non-builtin namespaces from ontology entities
        Set<String> namespaces = new HashSet<>();
        for (OWLEntity entity : ontology.getSignature()) {
            String ns = entity.getIRI().getNamespace();
            if (ns == null || ns.isEmpty()) continue;
            if (ns.contains("www.w3.org")) continue;
            if (defaultNs != null && ns.equals(defaultNs)) continue;
            namespaces.add(ns);
        }

        // Assign stable prefix names for new namespaces (never renumber existing ones)
        boolean newPrefixesAdded = false;
        int maxIdx = prefixMap.values().stream()
                .filter(p -> p.startsWith("ns") && p.endsWith(":"))
                .mapToInt(p -> { try { return Integer.parseInt(p.substring(2, p.length() - 1)); } catch (Exception e) { return 0; } })
                .max().orElse(0);

        for (String ns : namespaces) {
            if (!prefixMap.containsKey(ns)) {
                maxIdx++;
                String prefix = "ns" + maxIdx + ":";
                prefixMap.put(ns, prefix);
                newPrefixesAdded = true;
                logger.info("Assigned prefix '{}' for namespace '{}' in project {}", prefix, ns, projectId);
            }
        }

        // Register ALL project prefixes on the (freshly fetched) ontology format
        org.semanticweb.owlapi.formats.PrefixDocumentFormat pf = (org.semanticweb.owlapi.formats.PrefixDocumentFormat) format;
        for (Map.Entry<String, String> entry : prefixMap.entrySet()) {
            pf.setPrefix(entry.getValue(), entry.getKey());
        }

        // Always force-register the default namespace as ':' so SWRL4J can resolve bare entity
        // names like Employee(?x). GraphDB re-exports strip xmlns="" so ':' is absent. Other
        // prefixes like co: may already map to the same namespace but SWRL4J only uses ':'.
        if (defaultNs != null) {
            pf.setPrefix(":", defaultNs);
            newPrefixesAdded = true;
            logger.info("Registered default namespace '{}' as ':' prefix for SWRL4J entity resolution (project {})",
                    defaultNs, projectId);
        }

        return newPrefixesAdded;
    }

    /**
     * Resolve entity short names in SWRL rule text to prefixed form.
     * Handles entities from non-default namespaces (e.g., OBO ontology classes like CHEBI_16670).
     * SWRLAPI only resolves unprefixed names against the default namespace, so entities from
     * other namespaces are converted to prefixed form: ns1:BFO_0000015
     * NOTE: ensureNamespacePrefixes() must be called before this method to register prefixes.
     */
    private String resolveEntityNames(String ruleText, OWLOntology ontology) {
        if (ruleText == null || ruleText.isEmpty()) return ruleText;

        // Build map of shortName -> IRI for all entities in the ontology
        Map<String, IRI> shortNameToIRI = new HashMap<>();
        for (OWLEntity entity : ontology.getSignature()) {
            IRI iri = entity.getIRI();
            String shortForm = iri.getShortForm();
            if (shortForm != null && !shortForm.isEmpty() && shortForm.length() > 1) {
                if (shortNameToIRI.containsKey(shortForm)) {
                    shortNameToIRI.put(shortForm, null); // ambiguous
                } else {
                    shortNameToIRI.put(shortForm, iri);
                }
            }
        }
        shortNameToIRI.values().removeIf(Objects::isNull);
        if (shortNameToIRI.isEmpty()) return ruleText;

        // Determine the default namespace from ontology IRI
        String defaultNs = null;
        try {
            com.google.common.base.Optional<IRI> ontIRI = ontology.getOntologyID().getOntologyIRI();
            if (ontIRI.isPresent()) {
                defaultNs = ontIRI.get().toString();
                if (!defaultNs.endsWith("#") && !defaultNs.endsWith("/")) {
                    defaultNs = defaultNs + "#";
                }
            }
        } catch (Exception e) { /* ignore */ }

        // Read prefix mappings registered by ensureNamespacePrefixes()
        Map<String, String> nsToPrefix = new HashMap<>();
        OWLDocumentFormat format = ontology.getOWLOntologyManager().getOntologyFormat(ontology);
        if (format instanceof org.semanticweb.owlapi.formats.PrefixDocumentFormat) {
            ((org.semanticweb.owlapi.formats.PrefixDocumentFormat) format).getPrefixName2PrefixMap().forEach((prefixName, ns) -> {
                nsToPrefix.put(ns, prefixName);
            });
        }

        // Sort by name length descending to prevent partial replacements
        List<Map.Entry<String, IRI>> sorted = new ArrayList<>(shortNameToIRI.entrySet());
        sorted.sort((a, b) -> b.getKey().length() - a.getKey().length());

        String result = ruleText;
        for (Map.Entry<String, IRI> entry : sorted) {
            String name = entry.getKey();
            IRI iri = entry.getValue();
            String ns = iri.getNamespace();

            // Skip entities already in the default namespace (SWRLAPI resolves these)
            if (defaultNs != null && ns.equals(defaultNs)) continue;

            // Skip OWL/RDF/XSD built-in entities
            if (ns.contains("www.w3.org")) continue;

            // Match standalone name followed by '(' but not already prefixed (negative lookbehind for : or word char)
            String pattern = "(?<![:\\w])" + java.util.regex.Pattern.quote(name) + "(?=\\()";
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(result);
            if (!matcher.find()) continue;

            String prefix = nsToPrefix.get(ns);
            if (prefix == null) {
                logger.warn("No prefix registered for namespace '{}'. Entity '{}' may not resolve.", ns, name);
                continue;
            }

            String replacement = java.util.regex.Matcher.quoteReplacement(prefix + name);
            result = java.util.regex.Pattern.compile(pattern).matcher(result).replaceAll(replacement);
            logger.debug("Resolved SWRL entity '{}' -> {}{}", name, prefix, name);
        }

        if (!result.equals(ruleText)) {
            logger.info("Resolved SWRL rule text: {} -> {}", ruleText, result);
        }
        return result;
    }

    /**
     * ✅ FIXED: Track access time for cleanup
     * ✅ ENHANCED: Set up prefix mappings for better SWRL parsing
     */
    private SWRLRuleEngine getOrCreateEngine(String projectId, OWLOntology ontology) {
        engineLastAccess.put(projectId, System.currentTimeMillis());
        
        return engineCache.computeIfAbsent(projectId, id -> {
            logger.info("Creating new SWRL engine for project: {}", projectId);

            SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);
            
            try {
                com.google.common.base.Optional<IRI> ontologyIRIOptional = ontology.getOntologyID().getOntologyIRI();
                if (ontologyIRIOptional.isPresent()) {
                    logger.info("Ontology base IRI for project {}: {}", projectId, ontologyIRIOptional.get());
                    logger.info("Classes in ontology: {}", 
                        ontology.getClassesInSignature().stream()
                            .map(c -> c.getIRI().getShortForm())
                            .limit(10)
                            .collect(Collectors.joining(", ")));
                }
            } catch (Exception e) {
                logger.warn("Failed to log ontology info for project {}: {}", projectId, e.getMessage());
            }
            
            return engine;
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
        
        // Invalid predicate error
        if (errorMsg.contains("invalid") && errorMsg.contains("predicate")) {
            suggestions.add("The class, property, or individual does not exist in your ontology");
            suggestions.add("Check that the entity name exactly matches what's in your OWL file");
            suggestions.add("Ensure proper capitalization (classes usually start with uppercase)");
            suggestions.add("If using a different namespace, you may need to use full IRIs");
            suggestions.add("Example with full IRI: <http://example.org/ontology#BloodPressure>(?x)");
        }
        
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
            ensureNamespacePrefixes(projectId, ontology);
            SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);

            // Add and execute the test rule
            String testRuleName = "test_" + System.currentTimeMillis();
            String resolvedText = resolveEntityNames(ruleText, ontology);
            engine.createSWRLRule(testRuleName, resolvedText);

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
     * Test a single rule by ID
     */
    public ExecutionResult testSingleRuleById(String projectId, String ruleId) {
        SwrlRule rule = ruleRepository.findByIdAndProjectId(ruleId, projectId)
            .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + ruleId));
        
        return testSingleRule(projectId, rule.getRuleText());
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

    /**
     * Execute a SQWRL query and return tabular results
     */
    public SqwrlQueryResult executeSqwrlQuery(String projectId, String queryText, String queryName, Integer maxResults) {
        long startTime = System.currentTimeMillis();
        
        if (queryName == null || queryName.isBlank()) {
            queryName = "SQWRLQuery_" + System.currentTimeMillis();
        }
        
        int limit = (maxResults != null && maxResults > 0) ? maxResults : 100;
        
        try {
            OWLOntology ontology = ontologyClient.fetchOntology(projectId);
            ensureNamespacePrefixes(projectId, ontology);
            
            // Create SQWRL query engine
            org.swrlapi.sqwrl.SQWRLQueryEngine queryEngine = SWRLAPIFactory.createSQWRLQueryEngine(ontology);

            // SQWRL queries run on a fresh engine, so load the saved enabled
            // SWRL rules first. Otherwise queries for inferred classes (for
            // example HonorsStudent after a GPA rule) return no rows.
            List<SwrlRule> enabledRules = ruleRepository.findByProjectIdAndEnabled(projectId, true);
            for (SwrlRule rule : enabledRules) {
                try {
                    String resolvedRule = resolveEntityNames(rule.getRuleText(), ontology);
                    queryEngine.createSWRLRule(rule.getRuleName(), resolvedRule);
                } catch (org.swrlapi.parser.SWRLParseException e) {
                    logger.warn("Skipping saved SWRL rule '{}' while preparing SQWRL query: {}",
                            rule.getRuleName(), e.getMessage());
                }
            }
            
            logger.info("Executing SQWRL query for project {}: {}", projectId, queryText);
            
            // Execute the query - resolve entity names for non-default namespaces
            String resolvedQuery = resolveEntityNames(queryText, ontology);
            org.swrlapi.sqwrl.SQWRLResult sqwrlResult = queryEngine.runSQWRLQuery(queryName, resolvedQuery);
            
            long executionTime = System.currentTimeMillis() - startTime;
            
            // Extract column names
            List<String> columnNames = new ArrayList<>();
            int columnCount = sqwrlResult.getNumberOfColumns();
            for (int i = 0; i < columnCount; i++) {
                columnNames.add(sqwrlResult.getColumnName(i));
            }
            
            // Extract rows
            List<Map<String, String>> rows = new ArrayList<>();
            int rowIndex = 0;
            while (sqwrlResult.next() && rowIndex < limit) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < columnCount; i++) {
                    String colName = columnNames.get(i);
                    try {
                        org.swrlapi.sqwrl.values.SQWRLResultValue value = sqwrlResult.getValue(i);
                        row.put(colName, formatSqwrlValue(value));
                    } catch (Exception e) {
                        row.put(colName, "(error)");
                    }
                }
                rows.add(row);
                rowIndex++;
            }
            
            logger.info("SQWRL query returned {} rows in {}ms", rows.size(), executionTime);
            
            return new SqwrlQueryResult(true, queryName, queryText, executionTime, columnNames, rows, null);
            
        } catch (org.swrlapi.parser.SWRLParseException e) {
            logger.error("SQWRL parse error: {}", e.getMessage());
            return new SqwrlQueryResult(false, queryName, queryText, 0, null, null, 
                "Query syntax error: " + e.getMessage());
                
        } catch (org.swrlapi.sqwrl.exceptions.SQWRLException e) {
            logger.error("SQWRL execution error: {}", e.getMessage());
            return new SqwrlQueryResult(false, queryName, queryText, 0, null, null, 
                "Query execution error: " + e.getMessage());
                
        } catch (Exception e) {
            logger.error("SQWRL query failed for project {}", projectId, e);
            return new SqwrlQueryResult(false, queryName, queryText, 0, null, null, 
                "Query failed: " + e.getMessage());
        }
    }
    
    /**
     * Format a SQWRL result value to a string
     */
    private String formatSqwrlValue(org.swrlapi.sqwrl.values.SQWRLResultValue value) {
        if (value == null) {
            return "(null)";
        }
        
        if (value instanceof org.swrlapi.sqwrl.values.SQWRLNamedIndividualResultValue) {
            String iri = ((org.swrlapi.sqwrl.values.SQWRLNamedIndividualResultValue) value).getIRI().toString();
            // Extract local name from IRI
            if (iri.contains("#")) {
                return iri.substring(iri.lastIndexOf('#') + 1);
            } else if (iri.contains("/")) {
                return iri.substring(iri.lastIndexOf('/') + 1);
            }
            return iri;
        }
        
        if (value instanceof org.swrlapi.sqwrl.values.SQWRLClassResultValue) {
            String iri = ((org.swrlapi.sqwrl.values.SQWRLClassResultValue) value).getIRI().toString();
            if (iri.contains("#")) {
                return iri.substring(iri.lastIndexOf('#') + 1);
            }
            return iri;
        }
        
        if (value instanceof org.swrlapi.sqwrl.values.SQWRLLiteralResultValue) {
            return ((org.swrlapi.sqwrl.values.SQWRLLiteralResultValue) value).getValue();
        }
        
        return value.toString();
    }
    
    /**
     * Inner class for SQWRL query results
     */
    public static class SqwrlQueryResult {
        private boolean success;
        private String queryName;
        private String queryText;
        private long executionTimeMs;
        private List<String> columnNames;
        private List<Map<String, String>> rows;
        private String errorMessage;
        
        public SqwrlQueryResult(boolean success, String queryName, String queryText, 
                               long executionTimeMs, List<String> columnNames, 
                               List<Map<String, String>> rows, String errorMessage) {
            this.success = success;
            this.queryName = queryName;
            this.queryText = queryText;
            this.executionTimeMs = executionTimeMs;
            this.columnNames = columnNames;
            this.rows = rows;
            this.errorMessage = errorMessage;
        }
        
        public boolean isSuccess() { return success; }
        public String getQueryName() { return queryName; }
        public String getQueryText() { return queryText; }
        public long getExecutionTimeMs() { return executionTimeMs; }
        public List<String> getColumnNames() { return columnNames; }
        public List<Map<String, String>> getRows() { return rows; }
        public int getRowCount() { return rows != null ? rows.size() : 0; }
        public String getErrorMessage() { return errorMessage; }
    }
}