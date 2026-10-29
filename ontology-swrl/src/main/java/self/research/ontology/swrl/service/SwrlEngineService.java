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
import java.util.function.Predicate;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

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

    private final EngineCache<SWRLRuleEngine> engineCache = new EngineCache<>(20, engine -> logger.debug("Disposed an evicted SWRL engine"));

    @Value("${ontocode.capacity.swrl-execute.engine-cache-max-size:${swrl.engine.cache.max-size:20}}")
    public void setEngineCacheMaxSize(int maxSize) {
        engineCache.setMaxSize(maxSize);
    }

    @Value("${ontocode.capacity.swrl-execute.workers:${swrl.execution.workers:2}}")
    private int executionWorkers = 2;

    @Value("${ontocode.capacity.swrl-execute.queue:${swrl.execution.queue:10}}")
    private int executionQueue = 10;

    @Value("${ontocode.capacity.swrl-execute.per-owner-active:${swrl.execution.per-owner-active:1}}")
    private int perOwnerActive = 1;

    @Value("${ontocode.capacity.swrl-execute.per-owner-waiting:${swrl.execution.per-owner-waiting:1}}")
    private int perOwnerWaiting = 1;

    @Value("${ontocode.capacity.swrl-execute.max-wait-ms:${swrl.execution.max-wait-ms:10000}}")
    private long maxWaitMs = 10000;

    @Value("${ontocode.capacity.swrl-execute.sqwrl-max-rows:1000}")
    private int sqwrlMaxRows = 1000;

    @Value("${ontocode.capacity.swrl-execute.sqwrl-max-bytes:8388608}")
    private long sqwrlMaxBytes = 8L * 1024 * 1024;

    @Value("${ontocode.capacity.swrl-execute.sqwrl-count-limit:100000}")
    private int sqwrlCountLimit = 100000;

    @Value("${ontocode.capacity.swrl-execute.fetch-allowance-seconds:120}")
    private int fetchAllowanceSeconds = 120;

    @Value("${ontocode.capacity.swrl-validate.workers:1}")
    private int validationWorkers = 1;

    @Value("${ontocode.capacity.swrl-validate.queue:4}")
    private int validationQueue = 4;

    @Value("${ontocode.capacity.swrl-validate.max-wait-ms:5000}")
    private long validationMaxWaitMs = 5000;

    private volatile ExecutionGate executionGate;
    private volatile ExecutionGate validationGate;
    private final ValidationCache validationCache = new ValidationCache();

    private long taskBudgetSeconds() {
        return (long) executionTimeoutSeconds + Math.max(0, fetchAllowanceSeconds);
    }

    private <T> T admitted(Callable<T> task) throws Exception {
        return admitted(gate(), task);
    }

    private <T> T admitted(ExecutionGate selected, Callable<T> task) throws Exception {
        org.springframework.web.context.request.RequestAttributes attributes =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        String owner = OwnerContext.current();
        try {
            return selected.call(owner, () -> {
                org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(attributes);
                OwnerContext.set(owner);
                try {
                    return task.call();
                } finally {
                    OwnerContext.clear();
                    org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
                }
            }, taskBudgetSeconds());
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    ExecutionGate gate() {
        ExecutionGate current = executionGate;
        if (current == null) {
            synchronized (this) {
                if (executionGate == null) {
                    executionGate = new ExecutionGate(executionWorkers, executionQueue, perOwnerActive, perOwnerWaiting, maxWaitMs);
                }
                current = executionGate;
            }
        }
        return current;
    }

    ExecutionGate validationGate() {
        ExecutionGate current = validationGate;
        if (current == null) {
            synchronized (this) {
                if (validationGate == null) {
                    validationGate = new ExecutionGate(validationWorkers, validationQueue, perOwnerActive, perOwnerWaiting,
                            validationMaxWaitMs);
                }
                current = validationGate;
            }
        }
        return current;
    }

    public ExecutionGate.Stats executionStats() {
        return gate().stats();
    }

    @jakarta.annotation.PreDestroy
    void shutdownGate() {
        ExecutionGate current = executionGate;
        if (current != null) {
            current.shutdown();
        }
        ExecutionGate validating = validationGate;
        if (validating != null) {
            validating.shutdown();
        }
    }

    // Stable namespace-to-prefix mapping per project (ensures consistent prefix names across calls)
    private final Map<String, Map<String, String>> projectNamespacePrefixes = new ConcurrentHashMap<>();

    /**
     * ✅ NEW: Cleanup stale engines periodically
     */
    @Scheduled(fixedDelayString = "${swrl.cache-cleanup-interval-ms:3600000}") // Default: 1 hour
    public void cleanupStaleEngines() {
        long cutoffTime = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(engineCacheTtlHours);
        int removedCount = engineCache.evictOlderThan(cutoffTime);

        if (removedCount > 0) {
            logger.info("Cleaned up {} stale SWRL engines. Active engines: {}", 
                       removedCount, engineCache.size());
        }
    }

    /**
     * Translates SWRLAPI's "Invalid SWRL atom predicate '...'" into a message naming the
     * actual problem (an undeclared class/property) instead of a bare syntax error, since
     * that's the message a user reads. Returns null when the exception message doesn't match —
     * i.e. it's some other, genuine parse error.
     */
    private ValidationResult unknownEntityResult(String exceptionMessage, OWLOntology ontology) {
        String missing = RuleMessages.unknownName(exceptionMessage);
        if (missing == null) return null;
        List<String> suggestions = new ArrayList<>();
        List<String> close = RuleMessages.closestNames(missing, RuleMessages.namesIn(ontology), 3);
        if (!close.isEmpty()) {
            suggestions.add("Did you mean: " + String.join(", ", close) + "?");
        }
        suggestions.add("'" + missing + "' does not exist in this ontology.");
        suggestions.add("To infer new class membership: create the class '" + missing + "' first using the Entities panel, then use it in this rule.");
        suggestions.add("To use an existing class: check the spelling against the Entities panel.");
        return new ValidationResult(false, "Unknown class or property '" + missing + "'", suggestions);
    }

    public ValidationResult validateRule(String projectId, String ruleText) {
        return validateAdmitted(projectId, ruleText, null, null, true);
    }

    public ValidationResult validateRule(String projectId, String ruleText, String draftUserId) {
        return validateAdmitted(projectId, ruleText, draftUserId, null, true);
    }

    private ValidationResult validateAdmitted(String projectId, String ruleText, String draftUserId,
                                              org.swrlapi.core.SWRLAPIRule[] created, boolean light) {
        try {
            return admitted(light ? validationGate() : gate(), () -> validate(projectId, ruleText, draftUserId, created, light));
        } catch (TimeoutException e) {
            return new ValidationResult(false, "Validation timed out after " + taskBudgetSeconds()
                    + " seconds. Try again, or simplify the rule.", Collections.emptyList());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ValidationResult(false, "Validation was interrupted. Try again.", Collections.emptyList());
        } catch (SwrlBusyException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Validation error for project {}", projectId, e);
            return new ValidationResult(false, "Validation error: " + RuleMessages.explain(e.getMessage()), Collections.emptyList());
        }
    }

    private OWLOntology fetch(String projectId, String draftUserId) throws OWLOntologyCreationException {
        return draftUserId == null
                ? ontologyClient.fetchOntology(projectId)
                : ontologyClient.fetchOntologyWithStamp(projectId, draftUserId).ontology();
    }

    private ValidationCache.Entry buildValidationEntry(String projectId, String draftUserId, String stamp)
            throws OWLOntologyCreationException {
        OWLOntology ontology = fetch(projectId, draftUserId);
        ensureNamespacePrefixes(projectId, ontology);
        OntologyRules.removeAll(ontology);
        SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);
        return validationCache.put(new ValidationCache.Key(projectId, draftUserId, OwnerContext.current()), stamp,
                ontology, engine, ontologyClient.maxAxioms());
    }

    private ValidationCache.Entry validationEntry(String projectId, String draftUserId, boolean light)
            throws OWLOntologyCreationException {
        if (!light) {
            OWLOntology ontology = fetch(projectId, draftUserId);
            ensureNamespacePrefixes(projectId, ontology);
            OntologyRules.removeAll(ontology);
            return new ValidationCache.Entry(null, ontology, SWRLAPIFactory.createSWRLRuleEngine(ontology), 0);
        }
        String stamp = ontologyClient.currentStamp(projectId, draftUserId);
        ValidationCache.Key key = new ValidationCache.Key(projectId, draftUserId, OwnerContext.current());
        ValidationCache.Entry hit = stamp == null ? null : validationCache.get(key, stamp);
        if (hit != null) {
            engineLog.info("[VALIDATE] Reused the parsed ontology project={}", projectId);
            return hit;
        }
        if (stamp == null) {
            OWLOntology ontology = fetch(projectId, draftUserId);
            ensureNamespacePrefixes(projectId, ontology);
            OntologyRules.removeAll(ontology);
            return new ValidationCache.Entry(null, ontology, SWRLAPIFactory.createSWRLRuleEngine(ontology), 0);
        }
        return buildValidationEntry(projectId, draftUserId, stamp);
    }

    private ValidationResult validate(String projectId, String ruleText, String draftUserId,
                                      org.swrlapi.core.SWRLAPIRule[] created, boolean light) {
        long startTime = System.currentTimeMillis();
        engineLog.info("[VALIDATE] Starting validation project={} ruleLength={}", projectId, ruleText.length());
        Timer.Sample sample = meterRegistry != null ? Timer.start(meterRegistry) : null;
        
        try {
            long fetchStart = System.currentTimeMillis();
            ValidationCache.Entry entry = validationEntry(projectId, draftUserId, light);
            OWLOntology ontology = entry.ontology;
            SWRLRuleEngine validationEngine = entry.engine;
            engineLog.info("[VALIDATE] Ontology and engine ready in {}ms project={}", System.currentTimeMillis() - fetchStart, projectId);

            String tempName = "temp_validation_" + System.currentTimeMillis();
            synchronized (entry) {
            try {
            String resolvedText = resolveEntityNames(ruleText, ontology);

            try {
                org.swrlapi.core.SWRLAPIRule createdRule = validationEngine.createSWRLRule(tempName, resolvedText);
                if (created != null) {
                    created[0] = createdRule;
                }
            } catch (SWRLParseException parseEx) {
                // SWRLAPI throws SWRLParseException (not a bare Exception) for an unbound
                // atom predicate too — e.g. "Invalid SWRL atom predicate 'hasAge'" when hasAge
                // isn't declared in the ontology. Check for that pattern here as well, or this
                // case never reaches the friendly translation below and the user sees the raw
                // "Syntax error: Invalid SWRL atom predicate '...'" instead.
                ValidationResult unknownEntity = unknownEntityResult(parseEx.getMessage(), ontology);
                if (unknownEntity != null) return unknownEntity;
                // Otherwise a genuine syntax error — re-throw so the outer catch block handles
                // it with enhanced suggestions.
                throw parseEx;
            } catch (Exception createEx) {
                // createSWRLRule throws a non-parse exception when a class/property name in
                // the rule does not exist in the ontology (e.g. "Invalid SWRL atom predicate").
                ValidationResult unknownEntity = unknownEntityResult(createEx.getMessage(), ontology);
                if (unknownEntity != null) return unknownEntity;
                throw createEx;
            }

            // Semantic validation: run inference on the isolated engine to catch unbound
            // argument errors in built-ins. Only the one temp rule runs here.
            if (!light) try {
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

                    return new ValidationResult(false, "Built-in function error: " + RuleMessages.explain(errorMsg), suggestions);
                }

                throw inferEx;
            }
            } finally {
                if (light) {
                    try {
                        validationEngine.deleteSWRLRule(tempName);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
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
                
        } catch (OntologyTooLargeException e) {
            return new ValidationResult(false, e.getMessage(), Collections.emptyList());

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
                "Validation error: " + RuleMessages.explain(e.getMessage()), 
                Collections.emptyList());
        }
    }

    private static ExecutionResult failure(String completion, String message) {
        ExecutionResult result = new ExecutionResult(false, 0, 0, Collections.emptyList(), message);
        result.setCompletion(completion);
        return result;
    }

    public record EmbeddedRule(String id, String ruleName, String name, String ruleText, String comment, String category,
                               boolean enabled, String source, boolean readOnly, String notice) {
        public EmbeddedRule(String id, String ruleName, String name, String ruleText, String comment, String category,
                            boolean enabled, String source, boolean readOnly) {
            this(id, ruleName, name, ruleText, comment, category, enabled, source, readOnly, null);
        }
    }

    private static final String QUERY_NOTICE = "This is a SQWRL query. It is listed here but is not run as a rule.";
    private static final String UNCHECKED_NOTICE = "This rule is shown as stored in the ontology. The rule engine could not load it, so it cannot be run until it is fixed.";

    private static EmbeddedRule embedded(OntologyRules.Entry entry) {
        String notice = entry.query() ? QUERY_NOTICE : entry.textFromFallback() ? UNCHECKED_NOTICE : null;
        String category = entry.category() == null && entry.query() ? "SQWRL query" : entry.category();
        return new EmbeddedRule(entry.id(), entry.name(), entry.name(), entry.ruleText(), entry.comment(), category,
                entry.enabled(), entry.source(), entry.readOnly(), notice);
    }

    private List<OntologyRules.Entry> listEntries(String projectId, String draftUserId) throws Exception {
        OWLOntology ontology = ontologyClient.fetchOntologyWithStamp(projectId, draftUserId).ontology();
        try {
            ensureNamespacePrefixes(projectId, ontology);
        } catch (RuntimeException e) {
            logger.warn("Could not register the prefixes while listing the rules of project {}: {}", projectId, e.getMessage());
        }
        List<OntologyRules.Entry> entries = OntologyRules.prepare(ontology, entry -> true, true);
        try {
            SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);
            OntologyRules.fillTexts(engine, entries);
        } catch (RuntimeException e) {
            logger.warn("The rule engine could not load the rules of project {}, listing them from the ontology instead: {}",
                    projectId, e.getMessage());
        }
        OntologyRules.fillFallbackTexts(entries);
        entries.sort(java.util.Comparator.comparing(OntologyRules.Entry::name).thenComparing(OntologyRules.Entry::id));
        return entries;
    }

    public List<EmbeddedRule> listEmbeddedRules(String projectId, String draftUserId) throws Exception {
        return listEntries(projectId, draftUserId).stream().map(SwrlEngineService::embedded).collect(Collectors.toList());
    }

    public List<SwrlRule> listRulesAsRows(String projectId, String draftUserId) throws Exception {
        List<SwrlRule> rows = new ArrayList<>();
        for (OntologyRules.Entry entry : listEntries(projectId, draftUserId)) {
            SwrlRule row = new SwrlRule(projectId, entry.name(), entry.ruleText());
            row.setId(entry.id());
            row.setComment(entry.comment());
            row.setCategory(entry.category());
            row.setEnabled(entry.enabled());
            rows.add(row);
        }
        return rows;
    }

    public SwrlRule getRule(String projectId, String ruleId) throws Exception {
        return listRulesAsRows(projectId, null).stream()
                .filter(rule -> rule.getId().equals(ruleId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + ruleId));
    }

    public RuleStatistics getRuleStatistics(String projectId) throws Exception {
        List<SwrlRule> all = listRulesAsRows(projectId, null);
        long enabled = all.stream().filter(SwrlRule::isEnabled).count();
        String mostUsedCategory = all.stream()
                .filter(r -> r.getCategory() != null)
                .collect(Collectors.groupingBy(SwrlRule::getCategory, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        RuleStatistics stats = new RuleStatistics();
        stats.setTotalRules(all.size());
        stats.setEnabledRules(enabled);
        stats.setDisabledRules(all.size() - enabled);
        stats.setTotalExecutions(0);
        stats.setAverageExecutionTime(0);
        stats.setMostUsedCategory(mostUsedCategory);
        return stats;
    }

    public record RuleParse(boolean valid, String axiom, List<String> errors, List<String> suggestions) {
    }

    public RuleParse parseRule(String projectId, String ruleText, String draftUserId) {
        org.swrlapi.core.SWRLAPIRule[] created = new org.swrlapi.core.SWRLAPIRule[1];
        ValidationResult validation = validateAdmitted(projectId, ruleText, draftUserId, created, false);
        List<String> suggestions = validation.getSuggestions() == null ? List.of() : validation.getSuggestions();
        if (!validation.isValid() || created[0] == null) {
            String message = validation.getErrorMessage() != null ? validation.getErrorMessage() : "The rule could not be understood";
            return new RuleParse(false, null, List.of(message), suggestions);
        }
        OWLDataFactory factory = org.semanticweb.owlapi.apibinding.OWLManager.getOWLDataFactory();
        SWRLRule axiom = factory.getSWRLRule(new LinkedHashSet<>(created[0].getBody()), new LinkedHashSet<>(created[0].getHead()));
        try {
            return new RuleParse(true, functionalSyntax(List.of(axiom)), List.of(), suggestions);
        } catch (OWLOntologyCreationException | OWLOntologyStorageException e) {
            return new RuleParse(false, null, List.of("The rule could not be written out: " + e.getMessage()), suggestions);
        }
    }

    private static String functionalSyntax(Collection<SWRLRule> axioms) throws OWLOntologyCreationException, OWLOntologyStorageException {
        OWLOntologyManager manager = org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        OWLDataFactory factory = manager.getOWLDataFactory();
        OWLOntology result = manager.createOntology();
        Set<OWLEntity> entities = new LinkedHashSet<>();
        for (SWRLRule axiom : axioms) {
            manager.addAxiom(result, axiom);
            axiom.getSignature().stream().filter(entity -> !entity.isBuiltIn()).forEach(entities::add);
        }
        entities.forEach(entity -> manager.addAxiom(result, factory.getOWLDeclarationAxiom(entity)));
        org.semanticweb.owlapi.io.StringDocumentTarget target = new org.semanticweb.owlapi.io.StringDocumentTarget();
        manager.saveOntology(result, new org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat(), target);
        return target.toString();
    }

    public List<SwrlRule> legacyRules(String projectId) {
        return ruleRepository.findByProjectIdAndMigratedAtIsNull(projectId);
    }

    public SwrlRule markMigrated(String projectId, String ruleId) {
        SwrlRule rule = ruleRepository.findByIdAndProjectId(ruleId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + ruleId));
        rule.setMigratedAt(LocalDateTime.now());
        return ruleRepository.save(rule);
    }

    @CacheEvict(value = "executionResults", key = "#projectId")
    public ExecutionResult executeRules(String projectId) {
        return executeRules(projectId, null);
    }

    @CacheEvict(value = "executionResults", key = "#projectId")
    public ExecutionResult executeRules(String projectId, String draftUserId) {
        Timer.Sample sample = meterRegistry != null ? Timer.start(meterRegistry) : null;
        long startTime = System.currentTimeMillis();

        try {
            return admitted(() -> runEnabledRules(projectId, draftUserId, startTime, sample));

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
            
            return failure("timeout", "Execution timed out after " + taskBudgetSeconds() + " seconds");
                
        } catch (SwrlBusyException e) {
            throw e;
        } catch (OntologyTooLargeException e) {
            return failure("failed", e.getMessage());
        } catch (Exception e) {
            long totalDuration = System.currentTimeMillis() - startTime;
            if (e instanceof SWRLBuiltInException) {
                logger.error("SWRL built-in error for project {} after {}ms", projectId, totalDuration, e);
                return new ExecutionResult(false, 0, 0, Collections.emptyList(),
                    "Built-in function error: " + RuleMessages.explain(e.getMessage()));
            }
            logger.error("Execution error for project {} after {}ms", projectId, totalDuration, e);
            perfLog.info("[PERF] SWRL_EXECUTE project={} status=error duration={}ms error={}", projectId, totalDuration, e.getMessage());
            engineLog.error("[EXECUTE] Error in {}ms project={}: {}", totalDuration, projectId, e.getMessage());
            
            if (meterRegistry != null) {
                meterRegistry.counter("swrl.execution.errors",
                    "projectId", projectId,
                    "type", "system").increment();
            }
            
            return new ExecutionResult(false, 0, 0, Collections.emptyList(),
                "Execution error: " + RuleMessages.explain(e.getMessage()));
        }
    }

    private ExecutionResult runEnabledRules(String projectId, String draftUserId, long startTime, Timer.Sample sample) throws Exception {
        long fetchStart = System.currentTimeMillis();
        OntologyClientService.FetchedOntology fetched = ontologyClient.fetchOntologyWithStamp(projectId, draftUserId);
        OWLOntology ontology = fetched.ontology();
        long fetchDuration = System.currentTimeMillis() - fetchStart;
        engineLog.info("[EXECUTE] Ontology fetched in {}ms project={}", fetchDuration, projectId);

        ensureNamespacePrefixes(projectId, ontology);
        long engineStart = System.currentTimeMillis();
        List<OntologyRules.Entry> entries = OntologyRules.prepare(ontology, OntologyRules.Entry::enabled);
        SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);
        OntologyRules.fillTexts(engine, entries);
        long engineDuration = System.currentTimeMillis() - engineStart;
        engineLog.info("[EXECUTE] Fresh engine created in {}ms project={}", engineDuration, projectId);

        List<OntologyRules.Entry> enabledRules = new ArrayList<>();
        List<SkippedRule> skipped = new ArrayList<>();
        for (OntologyRules.Entry entry : entries) {
            if (!entry.enabled()) {
                continue;
            }
            if (entry.ruleText() == null) {
                skipped.add(new SkippedRule(entry.name(), "The rule could not be loaded by the rule engine."));
            } else {
                enabledRules.add(entry);
            }
        }
        logger.info("Executing {} enabled rules for project: {}", enabledRules.size(), projectId);
        engineLog.info("[EXECUTE] {} rules to execute project={}", enabledRules.size(), projectId);

        // ✅ FIXED: Execute with timeout
        Set<OWLAxiom> assertedBefore = InferredAxiomPresenter.assertionsOf(ontology);
        Inferred inferred = inferKeepingGoodRules(projectId, ontology, engine, enabledRules);
        Set<OWLAxiom> inferredAxioms = inferred.axioms();
        enabledRules = inferred.ran();
        skipped.addAll(inferred.failed());

        long executionTime = System.currentTimeMillis() - startTime;

        logger.info("Executed {} rules in {}ms, inferred {} axioms", 
                   enabledRules.size(), executionTime, inferredAxioms.size());

        InferredAxiomPresenter.Presented presented = InferredAxiomPresenter.present(inferredAxioms, assertedBefore, maxInferredAxioms);
        List<InferredAxiom> formattedAxioms = categorized(presented);

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
            .map(OntologyRules.Entry::name)
            .collect(Collectors.toList());

        ExecutionResult result = new ExecutionResult(
            true,
            executionTime,
            inferredAxioms.size(),
            formattedAxioms,
            null
        );
        result.setExecutedRuleNames(executedRuleNames);
        result.setExecutedRuleIds(enabledRules.stream().map(OntologyRules.Entry::id).collect(Collectors.toList()));
        result.setSkippedRules(skipped);
        applyPresentation(result, presented);
        result.setCompletion(skipped.isEmpty() ? "completed" : "completed_with_warnings");
        result.setExecutionMode("all");
        result.setStamp(new self.research.ontology.common.preview.PreviewStamp(
                draftUserId == null ? fetched.ontologyRevision() : null,
                draftUserId != null ? fetched.draftVersion() : null,
                self.research.ontology.common.preview.RulesFingerprint.of(enabledRules.stream()
                        .map(rule -> new String[]{rule.name(), rule.ruleText()})
                        .collect(Collectors.toList()))));
        perfLog.info("[PERF] SWRL_EXECUTE project={} rules={} inferred={} duration={}ms status=success",
                projectId, enabledRules.size(), inferredAxioms.size(), executionTime);
        engineLog.info("[EXECUTE] Completed: {} rules, {} inferred axioms in {}ms project={}",
                enabledRules.size(), inferredAxioms.size(), executionTime, projectId);
        return result;
    }

    /**
     * ✅ NEW: Execute selected rules by IDs
     */
    @CacheEvict(value = "executionResults", key = "#projectId")
    public ExecutionResult executeSelectedRules(String projectId, List<String> ruleIds) {
        Timer.Sample sample = meterRegistry != null ? Timer.start(meterRegistry) : null;
        long startTime = System.currentTimeMillis();

        try {
            return admitted(() -> runSelectedRules(projectId, ruleIds, startTime, sample));

        } catch (TimeoutException e) {
            logger.error("Selected rules execution timed out for project: {}", projectId);
            return failure("timeout", "Execution timed out after " + taskBudgetSeconds() + " seconds");
                
        } catch (SwrlBusyException e) {
            throw e;
        } catch (OntologyTooLargeException e) {
            return failure("failed", e.getMessage());
        } catch (Exception e) {
            logger.error("Selected rules execution error for project {}", projectId, e);
            return new ExecutionResult(false, 0, 0, Collections.emptyList(),
                "Execution error: " + RuleMessages.explain(e.getMessage()));
        }
    }

    private ExecutionResult runSelectedRules(String projectId, List<String> ruleIds, long startTime, Timer.Sample sample) throws Exception {
        OWLOntology ontology = ontologyClient.fetchOntology(projectId);
        ensureNamespacePrefixes(projectId, ontology);
        Set<String> requested = new LinkedHashSet<>(ruleIds);
        List<OntologyRules.Entry> entries = OntologyRules.prepare(ontology,
                entry -> entry.enabled() && (requested.contains(entry.id()) || requested.contains(entry.name())));
        SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);
        OntologyRules.fillTexts(engine, entries);

        List<OntologyRules.Entry> selectedRules = new ArrayList<>();
        List<SkippedRule> skipped = new ArrayList<>();
        Set<String> matched = new HashSet<>();
        for (OntologyRules.Entry entry : entries) {
            boolean chosen = requested.contains(entry.id()) || requested.contains(entry.name());
            if (!chosen) {
                continue;
            }
            matched.add(requested.contains(entry.id()) ? entry.id() : entry.name());
            if (!entry.enabled()) {
                skipped.add(new SkippedRule(entry.name(), "This rule is disabled."));
            } else if (entry.ruleText() == null) {
                skipped.add(new SkippedRule(entry.name(), "The rule could not be loaded by the rule engine."));
            } else {
                selectedRules.add(entry);
            }
        }
        for (String wanted : requested) {
            if (!matched.contains(wanted)) {
                skipped.add(new SkippedRule(wanted, "This rule was not found in this project."));
            }
        }
        if (selectedRules.isEmpty()) {
            ExecutionResult none = failure("failed", "No valid rules found for the given IDs");
            none.setSkippedRules(skipped);
            return none;
        }

        logger.info("Executing {} selected rules for project: {}", selectedRules.size(), projectId);

        // Execute with timeout
        Set<OWLAxiom> assertedBefore = InferredAxiomPresenter.assertionsOf(ontology);
        Inferred inferred = inferKeepingGoodRules(projectId, ontology, engine, selectedRules);
        Set<OWLAxiom> inferredAxioms = inferred.axioms();
        selectedRules = inferred.ran();
        skipped.addAll(inferred.failed());

        long executionTime = System.currentTimeMillis() - startTime;

        logger.info("Executed {} selected rules in {}ms, inferred {} axioms", 
                   selectedRules.size(), executionTime, inferredAxioms.size());

        InferredAxiomPresenter.Presented presented = InferredAxiomPresenter.present(inferredAxioms, assertedBefore, maxInferredAxioms);
        List<InferredAxiom> formattedAxioms = categorized(presented);

        if (sample != null) {
            sample.stop(Timer.builder("swrl.execution.selected")
                .tag("projectId", projectId)
                .tag("status", "success")
                .register(meterRegistry));
        }

        // Extract executed rule names
        List<String> executedRuleNames = selectedRules.stream()
            .map(OntologyRules.Entry::name)
            .collect(Collectors.toList());

        ExecutionResult result = new ExecutionResult(
            true,
            executionTime,
            inferredAxioms.size(),
            formattedAxioms,
            null
        );
        result.setExecutedRuleNames(executedRuleNames);
        result.setExecutedRuleIds(selectedRules.stream().map(OntologyRules.Entry::id).collect(Collectors.toList()));
        result.setSkippedRules(skipped);
        applyPresentation(result, presented);
        result.setCompletion(skipped.isEmpty() ? "completed" : "completed_with_warnings");
        result.setExecutionMode("selected");
        return result;
    }

    public record RulesExport(String text, List<SkippedRule> skipped, String fingerprint) {
    }

    public RulesExport exportEnabledRulesAsOwl(String projectId, String draftUserId) throws Exception {
        OWLOntology ontology = ontologyClient.fetchOntologyWithStamp(projectId, draftUserId).ontology();
        ensureNamespacePrefixes(projectId, ontology);
        List<OntologyRules.Entry> entries = OntologyRules.prepare(ontology, OntologyRules.Entry::enabled);
        SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);
        OntologyRules.fillTexts(engine, entries);

        OWLDataFactory factory = org.semanticweb.owlapi.apibinding.OWLManager.getOWLDataFactory();
        List<SkippedRule> skipped = new ArrayList<>();
        List<SWRLRule> axioms = new ArrayList<>();
        List<OntologyRules.Entry> exported = new ArrayList<>();
        for (OntologyRules.Entry entry : entries) {
            if (!entry.enabled()) {
                continue;
            }
            if (entry.ruleText() == null) {
                skipped.add(new SkippedRule(entry.name(), "The rule could not be loaded by the rule engine."));
                continue;
            }
            axioms.add((SWRLRule) entry.axiom().getAxiomWithoutAnnotations().getAnnotatedAxiom(Collections.singleton(
                    factory.getOWLAnnotation(factory.getRDFSLabel(), factory.getOWLLiteral(entry.engineName())))));
            exported.add(entry);
        }
        String text = functionalSyntax(axioms);
        String fingerprint = self.research.ontology.common.preview.RulesFingerprint.of(exported.stream()
                .map(rule -> new String[]{rule.name(), rule.ruleText()})
                .collect(Collectors.toList()));
        return new RulesExport(text, skipped, fingerprint);
    }

    public String inferOntologyRules(String ontologyText) throws Exception {
        return admitted(() -> inferOntologyRulesAdmitted(ontologyText));
    }

    private String inferOntologyRulesAdmitted(String ontologyText) throws Exception {
        OWLOntologyManager manager = org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(
                new org.semanticweb.owlapi.io.StringDocumentSource(ontologyText));
        PlainLiteralNormalizer.apply(ontology);
        OWLDocumentFormat format = manager.getOntologyFormat(ontology);
        if (format instanceof org.semanticweb.owlapi.formats.PrefixDocumentFormat prefixes) {
            prefixes.setPrefix("_:", "urn:ontocode:blank:");
        }
        Set<OWLAxiom> original = new HashSet<>(ontology.getAxioms());
        SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);
        Set<OWLAxiom> produced = runInference(engine, "ontology-rules");
        OWLOntology result = manager.createOntology();
        produced.stream()
                .filter(axiom -> axiom instanceof OWLClassAssertionAxiom
                        || axiom instanceof OWLObjectPropertyAssertionAxiom
                        || axiom instanceof OWLDataPropertyAssertionAxiom)
                .filter(axiom -> !original.contains(axiom))
                .limit(maxInferredAxioms * 100L)
                .forEach(axiom -> manager.addAxiom(result, axiom));
        org.semanticweb.owlapi.io.StringDocumentTarget target = new org.semanticweb.owlapi.io.StringDocumentTarget();
        manager.saveOntology(result, new org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat(), target);
        return target.toString();
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
    private static final int MAX_RULE_RETRIES = 3;
    private static final java.util.regex.Pattern FAILING_RULE_INVOKE = java.util.regex.Pattern.compile("invoker\\.invoke\\(\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final java.util.regex.Pattern FAILING_RULE_IN = java.util.regex.Pattern.compile("in rule ([^:]+?):");

    private record Inferred(Set<OWLAxiom> axioms, List<OntologyRules.Entry> ran, List<SkippedRule> failed) {
    }

    private static String chainMessage(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = error; current != null && text.length() < 4000; current = current.getCause() == current ? null : current.getCause()) {
            if (current.getMessage() != null) {
                text.append(current.getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    private static OntologyRules.Entry failingRule(String message, List<OntologyRules.Entry> candidates) {
        for (java.util.regex.Pattern pattern : List.of(FAILING_RULE_INVOKE, FAILING_RULE_IN)) {
            java.util.regex.Matcher matcher = pattern.matcher(message);
            while (matcher.find()) {
                String name = matcher.group(1).replace("\\\"", "\"");
                for (OntologyRules.Entry candidate : candidates) {
                    if (candidate.engineName().equals(name)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private Inferred inferKeepingGoodRules(String projectId, OWLOntology ontology, SWRLRuleEngine first,
                                           List<OntologyRules.Entry> candidates) {
        List<OntologyRules.Entry> ran = new ArrayList<>(candidates);
        List<SkippedRule> failed = new ArrayList<>();
        SWRLRuleEngine engine = first;
        for (int attempt = 0; ; attempt++) {
            try {
                return new Inferred(runInference(engine, projectId), ran, failed);
            } catch (RuntimeException e) {
                String message = chainMessage(e);
                OntologyRules.Entry culprit = attempt < MAX_RULE_RETRIES ? failingRule(message, ran) : null;
                if (culprit == null) {
                    throw e;
                }
                logger.warn("Rule {} failed in project {}, running the others without it: {}", culprit.name(), projectId, e.getMessage());
                String reason = e.getMessage() != null ? e.getMessage() : message;
                failed.add(new SkippedRule(culprit.name(), "Rule " + culprit.name() + " could not run: " + RuleMessages.explain(reason)));
                ran.remove(culprit);
                OntologyRules.drop(ontology, culprit);
                engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);
            }
        }
    }

    private Set<OWLAxiom> runInference(SWRLRuleEngine engine, String projectId) {
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
        pf.setPrefix("_:", "urn:ontocode:blank:");
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
        return RuleMessages.normalizeTextLiterals(resolveEntityNamesRaw(ruleText, ontology));
    }

    private String resolveEntityNamesRaw(String ruleText, OWLOntology ontology) {
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

    private List<InferredAxiom> categorized(InferredAxiomPresenter.Presented presented) {
        List<InferredAxiom> out = new ArrayList<>();
        for (OWLAxiom axiom : presented.ordered()) {
            InferredAxiom item = formatInferredAxiom(axiom);
            item.setCategory(presented.conclusions().contains(axiom) ? "conclusion" : "structure");
            out.add(item);
        }
        return out;
    }

    private static void applyPresentation(ExecutionResult result, InferredAxiomPresenter.Presented presented) {
        result.setConclusionCount(presented.conclusionCount());
        result.setStructureCount(presented.structureCount());
        result.setOmittedTrivialCount(presented.omittedTrivial());
        result.setTruncated(presented.truncated());
    }

    private InferredAxiom formatInferredAxiom(OWLAxiom axiom) {
        String axiomType = axiom.getAxiomType().getName();
        String description = axiom.toString();
        String readable = formatAxiomReadable(axiom);

        String subjectIri = null;
        String predicateIri = null;
        String objectIri = null;
        String objectLiteral = null;
        String literalDatatypeIri = null;
        String literalLangTag = null;

        if (axiom instanceof OWLClassAssertionAxiom classAssertion) {
            OWLIndividual individual = classAssertion.getIndividual();
            OWLClassExpression classExpr = classAssertion.getClassExpression();
            if (individual.isNamed() && !classExpr.isAnonymous()) {
                subjectIri = individual.asOWLNamedIndividual().getIRI().toString();
                predicateIri = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
                objectIri = classExpr.asOWLClass().getIRI().toString();
            }
        } else if (axiom instanceof OWLObjectPropertyAssertionAxiom objectAssertion) {
            OWLIndividual subject = objectAssertion.getSubject();
            OWLIndividual object = objectAssertion.getObject();
            if (subject.isNamed() && object.isNamed() && !objectAssertion.getProperty().isAnonymous()) {
                subjectIri = subject.asOWLNamedIndividual().getIRI().toString();
                predicateIri = objectAssertion.getProperty().asOWLObjectProperty().getIRI().toString();
                objectIri = object.asOWLNamedIndividual().getIRI().toString();
            }
        } else if (axiom instanceof OWLDataPropertyAssertionAxiom dataAssertion) {
            OWLIndividual subject = dataAssertion.getSubject();
            if (subject.isNamed() && !dataAssertion.getProperty().isAnonymous()) {
                subjectIri = subject.asOWLNamedIndividual().getIRI().toString();
                predicateIri = dataAssertion.getProperty().asOWLDataProperty().getIRI().toString();
                OWLLiteral literal = dataAssertion.getObject();
                objectLiteral = literal.getLiteral();
                literalDatatypeIri = literal.getDatatype().getIRI().toString();
                literalLangTag = literal.hasLang() ? literal.getLang() : null;
            }
        }

        return new InferredAxiom(axiomType, description, readable,
                subjectIri, predicateIri, objectIri, objectLiteral, literalDatatypeIri, literalLangTag);
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
        return engineCache.oldestAgeMillis();
    }

    private String getApproximateMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        return usedMemory + " MB";
    }

    /**
     * Test a single rule without saving
     */
    public ExecutionResult testSingleRule(String projectId, String ruleText) {
        long startTime = System.currentTimeMillis();
        try {
            return admitted(() -> runSingleRule(projectId, ruleText, startTime));
        } catch (SwrlBusyException e) {
            throw e;
        } catch (TimeoutException e) {
            logger.error("Test execution timed out for project {}", projectId);
            return failure("timeout", "Execution timed out after " + taskBudgetSeconds() + " seconds");
        } catch (OntologyTooLargeException e) {
            return new ExecutionResult(false, 0, 0, Collections.emptyList(), e.getMessage());
        } catch (Exception e) {
            logger.error("Test execution failed for project {}", projectId, e);
            return new ExecutionResult(false, 0, 0, Collections.emptyList(),
                    "Test failed: " + e.getMessage());
        }
    }

    private ExecutionResult runSingleRule(String projectId, String ruleText, long startTime) throws Exception {
        Timer.Sample sample = meterRegistry != null ? Timer.start(meterRegistry) : null;
        ValidationResult validation = validate(projectId, ruleText, null, null, false);
        if (!validation.isValid()) {
            return new ExecutionResult(false, 0, 0, Collections.emptyList(),
                    validation.getErrorMessage());
        }

        OWLOntology ontology = ontologyClient.fetchOntology(projectId);
        ensureNamespacePrefixes(projectId, ontology);
        OntologyRules.removeAll(ontology);
        SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);

        String testRuleName = "test_" + System.currentTimeMillis();
        String resolvedText = resolveEntityNames(ruleText, ontology);
        engine.createSWRLRule(testRuleName, resolvedText);

        Set<OWLAxiom> assertedBefore = InferredAxiomPresenter.assertionsOf(ontology);
        Set<OWLAxiom> inferredAxioms = runInference(engine, projectId);
        long executionTime = System.currentTimeMillis() - startTime;

        InferredAxiomPresenter.Presented presented = InferredAxiomPresenter.present(inferredAxioms, assertedBefore, maxInferredAxioms);
        List<InferredAxiom> formattedAxioms = categorized(presented).stream()
                .filter(item -> "conclusion".equals(item.getCategory()))
                .limit(maxInferredAxioms)
                .collect(Collectors.toList());
        boolean truncated = presented.conclusionCount() > formattedAxioms.size();

        if (sample != null) {
            sample.stop(Timer.builder("swrl.test")
                    .tag("projectId", projectId)
                    .register(meterRegistry));
        }

        ExecutionResult result = new ExecutionResult(true, executionTime, presented.conclusionCount(),
                formattedAxioms, null);
        result.setConclusionCount(presented.conclusionCount());
        result.setStructureCount(0);
        result.setOmittedTrivialCount(0);
        result.setTruncated(truncated);
        result.setExecutionMode("test");
        return result;
    }

    public ExecutionResult testSingleRuleById(String projectId, String ruleId) throws Exception {
        long startTime = System.currentTimeMillis();
        try {
            return admitted(() -> runSingleRuleById(projectId, ruleId, startTime));
        } catch (TimeoutException e) {
            return failure("timeout", "Execution timed out after " + taskBudgetSeconds() + " seconds");
        } catch (OntologyTooLargeException e) {
            return new ExecutionResult(false, 0, 0, Collections.emptyList(), e.getMessage());
        }
    }

    private ExecutionResult runSingleRuleById(String projectId, String ruleId, long startTime) throws Exception {
        OntologyRules.Entry found = null;
        for (OntologyRules.Entry entry : listEntries(projectId, null)) {
            if (entry.id().equals(ruleId)) {
                found = entry;
                break;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("Rule not found: " + ruleId);
        }
        ExecutionResult result = runSingleRule(projectId, found.ruleText(), startTime);
        result.setExecutedRuleNames(List.of(found.name()));
        result.setExecutedRuleIds(List.of(found.id()));
        return result;
    }

    /**
     * Execute a SQWRL query and return tabular results
     */
    public SqwrlQueryResult executeSqwrlQuery(String projectId, String queryText, String queryName, Integer maxResults) {
        long startTime = System.currentTimeMillis();
        
        if (queryName == null || queryName.isBlank()) {
            queryName = "SQWRLQuery_" + System.currentTimeMillis();
        }
        
        int hardCap = Math.max(1, sqwrlMaxRows);
        int limit = (maxResults != null && maxResults > 0) ? Math.min(maxResults, hardCap) : hardCap;
        
        try {
            final String runName = queryName;
            SqwrlRun run;
            try {
                run = admitted(() -> runSqwrl(projectId, queryText, runName, limit));
            } catch (TimeoutException e) {
                logger.error("SQWRL query timed out for project: {}", projectId);
                return new SqwrlQueryResult(false, queryName, queryText, 0, null, null,
                    "Query timed out after " + taskBudgetSeconds() + " seconds");
            }

            long executionTime = System.currentTimeMillis() - startTime;
            List<String> columnNames = run.columnNames();
            SqwrlRead read = run.read();
            List<Map<String, String>> rows = read.rows();
            int total = read.total();
            boolean exact = read.exact();
            boolean bytesCapped = read.bytesCapped();

            logger.info("SQWRL query returned {} of {} rows in {}ms", rows.size(), total, executionTime);

            boolean truncated = total > rows.size();
            return new SqwrlQueryResult(true, queryName, queryText, executionTime, columnNames, rows, null,
                    total, exact, truncated, limit, bytesCapped);
            
        } catch (org.swrlapi.parser.SWRLParseException e) {
            logger.error("SQWRL parse error: {}", e.getMessage());
            return new SqwrlQueryResult(false, queryName, queryText, 0, null, null, 
                "Query syntax error: " + e.getMessage());
                
        } catch (org.swrlapi.sqwrl.exceptions.SQWRLException e) {
            logger.error("SQWRL execution error: {}", e.getMessage());
            return new SqwrlQueryResult(false, queryName, queryText, 0, null, null, 
                "Query execution error: " + e.getMessage());
                
        } catch (SwrlBusyException e) {
            throw e;
        } catch (OntologyTooLargeException e) {
            return new SqwrlQueryResult(false, queryName, queryText, 0, null, null, e.getMessage());
        } catch (Exception e) {
            logger.error("SQWRL query failed for project {}", projectId, e);
            return new SqwrlQueryResult(false, queryName, queryText, 0, null, null, 
                "Query failed: " + e.getMessage());
        }
    }
    
    private record SqwrlRun(List<String> columnNames, SqwrlRead read) {
    }

    private SqwrlRun runSqwrl(String projectId, String queryText, String runName, int limit) throws Exception {
        OWLOntology ontology = ontologyClient.fetchOntology(projectId);
        ensureNamespacePrefixes(projectId, ontology);
        OntologyRules.prepare(ontology, OntologyRules.Entry::enabled);
        org.swrlapi.sqwrl.SQWRLQueryEngine queryEngine = SWRLAPIFactory.createSQWRLQueryEngine(ontology);

        logger.info("Executing SQWRL query for project {}: {}", projectId, queryText);

        String resolvedQuery = resolveEntityNames(queryText, ontology);
        org.swrlapi.sqwrl.SQWRLResult sqwrlResult = queryEngine.runSQWRLQuery(runName, resolvedQuery);

        List<String> columnNames = new ArrayList<>();
        int columnCount = sqwrlResult.getNumberOfColumns();
        for (int i = 0; i < columnCount; i++) {
            columnNames.add(sqwrlResult.getColumnName(i));
        }
        SqwrlRead read = readSqwrlRows(sqwrlResult, columnNames, limit, sqwrlMaxBytes, Math.max(sqwrlCountLimit, limit), this::formatSqwrlValue);
        return new SqwrlRun(columnNames, read);
    }

    public record SqwrlRead(List<Map<String, String>> rows, int total, boolean exact, boolean bytesCapped) {
    }

    static SqwrlRead readSqwrlRows(org.swrlapi.sqwrl.SQWRLResult result, List<String> columnNames, int limit, long maxBytes,
                                   int countLimit, java.util.function.Function<org.swrlapi.sqwrl.values.SQWRLResultValue, String> format)
            throws org.swrlapi.sqwrl.exceptions.SQWRLException {
        List<Map<String, String>> rows = new ArrayList<>();
        long bytes = 0;
        boolean bytesCapped = false;
        int total = 0;
        boolean exact = true;
        int columnCount = columnNames.size();
        while (result.next()) {
            if (total >= countLimit) {
                exact = false;
                break;
            }
            total++;
            if (rows.size() >= limit || bytesCapped) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            long rowBytes = 0;
            for (int i = 0; i < columnCount; i++) {
                String cell;
                try {
                    cell = format.apply(result.getValue(i));
                } catch (Exception e) {
                    cell = "(error)";
                }
                if (cell != null) {
                    rowBytes += cell.length();
                }
                row.put(columnNames.get(i), cell);
            }
            if (bytes + rowBytes > maxBytes) {
                bytesCapped = true;
                continue;
            }
            bytes += rowBytes;
            rows.add(row);
        }
        return new SqwrlRead(rows, total, exact, bytesCapped);
    }

    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    private static final String WHY_NOT_SCOPE = "Checks one named individual as the root variable of the rule's first class or property atom; "
            + "every other variable is enumerated from the asserted and derived facts, up to "
            + RuleEvidence.CANDIDATE_LIMIT + " candidate bindings. Supported atoms: class, object property, data property and the built-ins "
            + "greaterThan, lessThan, greaterThanOrEqual, lessThanOrEqual, equal, notEqual, add, subtract, multiply, divide, mod, contains, stringConcat.";

    private record EvidenceBase(OWLOntology ontology, RuleEvidence.Facts facts, Map<OntologyRules.Entry, org.swrlapi.core.SWRLAPIRule> parsed,
                                List<String> notes, org.swrlapi.core.SWRLAPIRule extra, OntologyRules.Entry extraEntry) {
    }

    private EvidenceBase prepareEvidence(String projectId, String draftUserId, String extraRuleId) throws Exception {
        OWLOntology ontology = ontologyClient.fetchOntologyWithStamp(projectId, draftUserId).ontology();
        ensureNamespacePrefixes(projectId, ontology);
        Set<OWLAxiom> asserted = InferredAxiomPresenter.assertionsOf(ontology);
        List<OntologyRules.Entry> entries = OntologyRules.prepare(ontology, OntologyRules.Entry::enabled);
        SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);
        OntologyRules.fillTexts(engine, entries);

        Map<OntologyRules.Entry, org.swrlapi.core.SWRLAPIRule> parsed = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        OntologyRules.Entry extraEntry = null;
        for (OntologyRules.Entry entry : entries) {
            if (extraRuleId != null && entry.id().equals(extraRuleId)) {
                extraEntry = entry;
            }
            if (!entry.enabled()) {
                continue;
            }
            if (entry.ruleText() == null) {
                notes.add("Rule '" + entry.name() + "' was skipped because it could not be read");
                continue;
            }
            parsed.put(entry, engine.getSWRLRule(entry.engineName()).orElse(null));
        }
        if (extraRuleId != null && extraEntry == null) {
            throw new IllegalArgumentException("Rule not found: " + extraRuleId);
        }
        Set<OWLAxiom> inferred = runInference(engine, projectId);
        org.swrlapi.core.SWRLAPIRule extra = null;
        if (extraEntry != null) {
            if (extraEntry.enabled() && extraEntry.ruleText() != null) {
                extra = engine.getSWRLRule(extraEntry.engineName()).orElse(null);
            } else {
                if (extraEntry.ruleText() == null) {
                    throw new IllegalArgumentException("The rule could not be read");
                }
                try {
                    extra = engine.createSWRLRule("why-not-" + UUID.randomUUID(), resolveEntityNames(extraEntry.ruleText(), ontology));
                } catch (SWRLParseException e) {
                    throw new IllegalArgumentException("The rule could not be read: " + e.getMessage());
                }
            }
        }
        return new EvidenceBase(ontology, RuleEvidence.Facts.of(asserted, inferred), parsed, notes, extra, extraEntry);
    }

    private static IRI resolveIri(OWLOntology ontology, String text) {
        String wanted = text.trim();
        List<IRI> byShortForm = new ArrayList<>();
        for (OWLEntity entity : ontology.getSignature(org.semanticweb.owlapi.model.parameters.Imports.INCLUDED)) {
            if (entity.getIRI().toString().equals(wanted)) {
                return entity.getIRI();
            }
            if (entity.getIRI().getShortForm().equals(wanted)) {
                byShortForm.add(entity.getIRI());
            }
        }
        return byShortForm.size() == 1 ? byShortForm.get(0) : IRI.create(wanted);
    }

    private static OWLLiteral literalFromText(String text) {
        OWLDataFactory factory = org.semanticweb.owlapi.apibinding.OWLManager.getOWLDataFactory();
        String trimmed = text.trim();
        try {
            new java.math.BigDecimal(trimmed);
            return trimmed.matches("[+-]?\\d+")
                    ? factory.getOWLLiteral(trimmed, org.semanticweb.owlapi.vocab.OWL2Datatype.XSD_INTEGER)
                    : factory.getOWLLiteral(trimmed, org.semanticweb.owlapi.vocab.OWL2Datatype.XSD_DECIMAL);
        } catch (NumberFormatException e) {
            return factory.getOWLLiteral(text);
        }
    }

    private static boolean seedTerm(Map<String, Object> seed, RuleEvidence.Term term, Object value) {
        if (value == null) {
            return true;
        }
        if (!term.isVariable()) {
            return RuleEvidence.valuesEqual(term.constant(), value);
        }
        Object existing = seed.get(term.variable());
        if (existing == null) {
            seed.put(term.variable(), value);
            return true;
        }
        return RuleEvidence.valuesEqual(existing, value);
    }

    public ExplainResponse explainConclusion(String projectId, ExplainRequest request, String draftUserId) throws Exception {
        try {
            return admitted(() -> buildExplanation(projectId, request, draftUserId));
        } catch (TimeoutException e) {
            throw new IllegalStateException("Rule evaluation timed out after " + taskBudgetSeconds() + " seconds");
        }
    }

    private ExplainResponse buildExplanation(String projectId, ExplainRequest request, String draftUserId) throws Exception {
        if (request == null || request.subject() == null || request.subject().isBlank()
                || request.predicate() == null || request.predicate().isBlank()) {
            throw new IllegalArgumentException("subject and predicate are required");
        }
        EvidenceBase base = prepareEvidence(projectId, draftUserId, null);
        RuleEvidence.Facts facts = base.facts();
        IRI subject = resolveIri(base.ontology(), request.subject());
        boolean isType = RDF_TYPE.equals(request.predicate().trim()) || "rdf:type".equals(request.predicate().trim());
        IRI predicate;
        String kind;
        IRI objectIri = null;
        OWLLiteral literal = null;
        if (isType) {
            if (request.object() == null || request.object().isBlank()) {
                throw new IllegalArgumentException("object (the class IRI) is required when predicate is rdf:type");
            }
            kind = "class";
            predicate = resolveIri(base.ontology(), request.object());
        } else {
            predicate = resolveIri(base.ontology(), request.predicate());
            if (request.object() != null && !request.object().isBlank()) {
                kind = "object";
                objectIri = resolveIri(base.ontology(), request.object());
            } else {
                kind = "data";
                if (request.objectLiteral() != null) {
                    literal = literalFromText(request.objectLiteral());
                }
            }
        }

        String conclusionText;
        boolean present;
        boolean asserted;
        if ("class".equals(kind)) {
            conclusionText = predicate.getShortForm() + "(" + subject.getShortForm() + ")";
            present = facts.hasClass(predicate, subject);
            asserted = facts.isClassFactAsserted(predicate, subject);
        } else {
            Object target = "object".equals(kind) ? objectIri : literal;
            Map<IRI, List<Object[]>> source = "object".equals(kind) ? facts.objects : facts.data;
            present = false;
            asserted = false;
            for (Object[] edge : source.getOrDefault(predicate, List.of())) {
                if (edge[0].equals(subject) && (target == null || RuleEvidence.valuesEqual(edge[1], target))) {
                    present = true;
                    asserted |= (Boolean) edge[2];
                }
            }
            conclusionText = predicate.getShortForm() + "(" + subject.getShortForm() + ", "
                    + (target == null ? "?" : RuleEvidence.display(target)) + ")";
        }

        List<String> notes = new ArrayList<>(base.notes());
        List<ExplainResponse.Justification> justifications = new ArrayList<>();
        for (Map.Entry<OntologyRules.Entry, org.swrlapi.core.SWRLAPIRule> entry : base.parsed().entrySet()) {
            OntologyRules.Entry rule = entry.getKey();
            List<Map<String, Object>> seeds = new ArrayList<>();
            for (SWRLAtom headAtom : entry.getValue().getHeadAtoms()) {
                RuleEvidence.Step head;
                try {
                    head = RuleEvidence.parse(List.of(headAtom)).get(0);
                } catch (RuleEvidence.Unsupported e) {
                    continue;
                }
                if (head.isBuiltin() || !head.kind().equals(kind) || !head.predicate().equals(predicate)) {
                    continue;
                }
                Map<String, Object> seed = new LinkedHashMap<>();
                boolean ok = seedTerm(seed, head.args().get(0), subject);
                if ("object".equals(kind)) {
                    ok = ok && seedTerm(seed, head.args().get(1), objectIri);
                } else if ("data".equals(kind)) {
                    ok = ok && seedTerm(seed, head.args().get(1), literal);
                }
                if (ok) {
                    seeds.add(seed);
                }
            }
            if (seeds.isEmpty()) {
                continue;
            }
            List<RuleEvidence.Step> steps;
            try {
                steps = RuleEvidence.parse(entry.getValue().getBodyAtoms());
            } catch (RuleEvidence.Unsupported e) {
                notes.add("Rule '" + rule.name() + "' not explained: unsupported " + e.getMessage());
                continue;
            }
            Set<String> seen = new HashSet<>();
            for (Map<String, Object> seed : seeds) {
                RuleEvidence.Solver solver = new RuleEvidence.Solver(facts, steps, 100);
                try {
                    solver.run(seed);
                } catch (RuleEvidence.Unsupported e) {
                    notes.add("Rule '" + rule.name() + "' not explained: unsupported " + e.getMessage());
                    continue;
                }
                for (Map<String, Object> binding : solver.solutions) {
                    if (!seen.add(binding.toString())) {
                        continue;
                    }
                    List<ExplainResponse.Premise> premises = new ArrayList<>();
                    for (RuleEvidence.Step step : steps) {
                        premises.add(new ExplainResponse.Premise(RuleEvidence.premiseKind(step, binding, facts),
                                RuleEvidence.instantiate(step, binding)));
                    }
                    justifications.add(new ExplainResponse.Justification(rule.name(), rule.ruleText(),
                            shownBinding(binding), premises));
                }
                if (solver.limitHit) {
                    notes.add("Rule '" + rule.name() + "': the search stopped after " + RuleEvidence.CANDIDATE_LIMIT
                            + " candidate bindings, so more justifications may exist");
                } else if (solver.solutions.size() >= 100) {
                    notes.add("Rule '" + rule.name() + "': only the first 100 justifications are shown");
                }
            }
        }

        String status;
        if (!justifications.isEmpty()) {
            status = "explained";
            if (asserted) {
                notes.add("asserted fact: it is also stated directly in the ontology");
            }
        } else if (asserted) {
            status = "origin_only";
            notes.add("asserted fact");
        } else if (present) {
            status = "origin_only";
            notes.add("Produced by OWL reasoning or a rule this explanation cannot evaluate");
        } else {
            status = "not_found";
            notes.add("This is not an asserted fact and the engine does not derive it, so there is nothing to explain");
        }
        return new ExplainResponse(conclusionText, status, justifications, notes);
    }

    public WhyNotResponse whyNotFired(String projectId, String ruleId, WhyNotRequest request, String draftUserId) throws Exception {
        try {
            return admitted(() -> buildWhyNot(projectId, ruleId, request, draftUserId));
        } catch (TimeoutException e) {
            throw new IllegalStateException("Rule evaluation timed out after " + taskBudgetSeconds() + " seconds");
        }
    }

    private WhyNotResponse buildWhyNot(String projectId, String ruleId, WhyNotRequest request, String draftUserId) throws Exception {
        if (request == null || request.individual() == null || request.individual().isBlank()) {
            throw new IllegalArgumentException("individual is required");
        }
        EvidenceBase base = prepareEvidence(projectId, draftUserId, ruleId);
        OntologyRules.Entry rule = base.extraEntry();
        IRI individual = resolveIri(base.ontology(), request.individual());
        String name = individual.getShortForm();

        List<WhyNotResponse.AtomReport> unsupportedReport = new ArrayList<>();
        List<RuleEvidence.Step> steps = new ArrayList<>();
        String unsupportedReason = null;
        for (SWRLAtom atom : base.extra().getBodyAtoms()) {
            try {
                RuleEvidence.Step step = RuleEvidence.parse(List.of(atom)).get(0).withIndex(steps.size());
                steps.add(step);
                unsupportedReport.add(new WhyNotResponse.AtomReport(step.text(), "not_evaluated", "not checked"));
            } catch (RuleEvidence.Unsupported e) {
                String text = atom instanceof SWRLBuiltInAtom b ? "swrlb:" + b.getPredicate().getShortForm() + "(...)" : atom.toString();
                unsupportedReport.add(new WhyNotResponse.AtomReport(text, "unsupported", "unsupported " + e.getMessage()));
                if (unsupportedReason == null) {
                    unsupportedReason = "unsupported " + e.getMessage();
                }
            }
        }
        if (unsupportedReason != null) {
            return new WhyNotResponse(rule.name(), name, "unsupported",
                    "This check cannot evaluate the rule: it uses " + unsupportedReason, Map.of(), unsupportedReport,
                    WHY_NOT_SCOPE, base.notes());
        }

        String rootVariable = null;
        for (RuleEvidence.Step step : steps) {
            if (step.isBuiltin()) {
                continue;
            }
            RuleEvidence.Term first = step.args().get(0);
            RuleEvidence.Term second = step.args().size() > 1 ? step.args().get(1) : null;
            if (first.isVariable()) {
                rootVariable = first.variable();
            } else if ("object".equals(step.kind()) && second != null && second.isVariable()) {
                rootVariable = second.variable();
            }
            if (rootVariable != null) {
                break;
            }
        }
        if (rootVariable == null) {
            return new WhyNotResponse(rule.name(), name, "unsupported",
                    "This check needs a class or property atom with a variable that can stand for the individual, and this rule has none",
                    Map.of(), unsupportedReport, WHY_NOT_SCOPE, base.notes());
        }

        RuleEvidence.Solver solver = new RuleEvidence.Solver(base.facts(), steps, 1);
        try {
            solver.run(Map.of(rootVariable, individual));
        } catch (RuleEvidence.Unsupported e) {
            return new WhyNotResponse(rule.name(), name, "unsupported",
                    "This check cannot evaluate the rule: " + e.getMessage(), Map.of(), unsupportedReport,
                    WHY_NOT_SCOPE, base.notes());
        }

        List<String> notes = new ArrayList<>(base.notes());
        List<WhyNotResponse.AtomReport> atoms = new ArrayList<>();
        if (!solver.solutions.isEmpty()) {
            Map<String, Object> binding = solver.solutions.get(0);
            for (RuleEvidence.Step step : steps) {
                atoms.add(new WhyNotResponse.AtomReport(step.text(), "satisfied", RuleEvidence.instantiate(step, binding)));
            }
            return new WhyNotResponse(rule.name(), name, "fires",
                    "The rule's conditions are all met for " + name, shownBinding(binding), atoms, WHY_NOT_SCOPE, notes);
        }
        RuleEvidence.Failure best = solver.best;
        if (best == null) {
            return new WhyNotResponse(rule.name(), name, "incomplete",
                    "No complete match was found and no failing atom was recorded", Map.of(), unsupportedReport, WHY_NOT_SCOPE, notes);
        }
        for (RuleEvidence.Step step : steps) {
            if (best.done()[step.index()]) {
                atoms.add(new WhyNotResponse.AtomReport(step.text(), "satisfied", RuleEvidence.instantiate(step, best.binding())));
            } else if (step.index() == best.stepIndex()) {
                atoms.add(new WhyNotResponse.AtomReport(step.text(), "failed", best.detail()));
            } else {
                atoms.add(new WhyNotResponse.AtomReport(step.text(), "not_evaluated", "not reached"));
            }
        }
        if (solver.deadEnds > 1) {
            notes.add(solver.deadEnds + " different bindings were tried and none satisfied every atom; the closest one is shown");
        }
        String result = best.result();
        String summary = best.detail();
        if (solver.limitHit) {
            result = "incomplete";
            summary = "Stopped after " + RuleEvidence.CANDIDATE_LIMIT + " candidate bindings without finding a complete match; closest attempt: " + best.detail();
        }
        return new WhyNotResponse(rule.name(), name, result, summary, shownBinding(best.binding()), atoms,
                WHY_NOT_SCOPE, notes);
    }

    private static Map<String, String> shownBinding(Map<String, Object> binding) {
        Map<String, String> shown = new LinkedHashMap<>();
        binding.forEach((variable, value) -> shown.put(variable, RuleEvidence.display(value)));
        return shown;
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
        
        private int totalRows;
        private boolean totalRowsExact = true;
        private boolean truncated;
        private int rowLimit;
        private boolean bytesCapped;

        public SqwrlQueryResult(boolean success, String queryName, String queryText,
                               long executionTimeMs, List<String> columnNames,
                               List<Map<String, String>> rows, String errorMessage,
                               int totalRows, boolean totalRowsExact, boolean truncated, int rowLimit, boolean bytesCapped) {
            this(success, queryName, queryText, executionTimeMs, columnNames, rows, errorMessage);
            this.totalRows = totalRows;
            this.totalRowsExact = totalRowsExact;
            this.truncated = truncated;
            this.rowLimit = rowLimit;
            this.bytesCapped = bytesCapped;
        }

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
        public int getTotalRows() { return totalRows; }
        public boolean isTotalRowsExact() { return totalRowsExact; }
        public boolean isTruncated() { return truncated; }
        public int getRowLimit() { return rowLimit; }
        public boolean isBytesCapped() { return bytesCapped; }
    }
}