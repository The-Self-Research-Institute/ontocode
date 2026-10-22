package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.reasoner.*;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import openllet.owlapi.OpenlletReasonerFactory;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import uk.ac.manchester.cs.jfact.JFactFactory;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Service for ontology reasoning operations.
 * Supports multiple reasoners: HermiT, Pellet (Openllet), FaCT++, ELK, and Structural.
 */
@Service("owlEditorReasonerService")
public class ReasonerService {

    private static final Logger log = LoggerFactory.getLogger(ReasonerService.class);

    private static final int INFERRED_AXIOM_COUNT_MAX_CLASSES = 2000;

    private enum Warmup { ALL, CLASS_HIERARCHY, NONE }

    private final Set<CompletableFuture<OWLReasoner>> partialWarmupBuilds =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Caffeine cache: max 5 reasoners, evict after 30 min idle, dispose on removal
    // (each reasoner holds the full ontology + inference state in heap)
    private final Cache<String, CompletableFuture<OWLReasoner>> reasonerCache = Caffeine.newBuilder()
        .maximumSize(5)
        .expireAfterAccess(15, TimeUnit.MINUTES)
        .removalListener((String key, CompletableFuture<OWLReasoner> future, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
            if (future != null) {
                partialWarmupBuilds.remove(future);
                future.thenAccept(reasoner -> {
                    log.info("[ReasonerCache] Disposing reasoner: {} (cause={})", key, cause);
                    try { reasoner.dispose(); } catch (Exception ignored) {}
                });
            }
        })
        .build();

    // HermiT's InstanceManager.getTypes() does O(n) LinkedList scans internally,
    // which blows up to O(n^2) against the class count on large ontologies (e.g.
    // 39k+ classes) and can run for hours on a single individual. That work isn't
    // cancellable once started, so we fire it on a daemon thread and abandon it
    // on timeout rather than block the caller indefinitely.
    // Desktop is single-user/single-request, so it can afford a much longer budget
    // than a shared cloud deployment (see application-desktop.properties overrides).
    // Same property name/default as plugin-service's ReasonerService — this is a
    // separate hardcoded copy of the same constant, not read from the same field.
    @Value("${ontocode.reasoner.per-individual-timeout-ms:5000}")
    private long PER_INDIVIDUAL_TYPE_TIMEOUT_MS;

    @Value("${ontocode.reasoner.save-consistency-timeout-ms:8000}")
    private long SAVE_CONSISTENCY_TIMEOUT_MS;

    @Value("${ontocode.reasoner.save-consistency-setup-timeout-ms:8000}")
    private long SAVE_CONSISTENCY_SETUP_TIMEOUT_MS;

    @Value("${ontocode.reasoner.operation-timeout-ms:45000}")
    private long reasonerOperationTimeoutMs;
    private final ExecutorService inferredTypesExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "reasoner-inferred-types-worker");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService saveConsistencyExecutor = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10), r -> {
                Thread t = new Thread(r, "reasoner-save-consistency-worker");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());

    private final ExecutorService saveConsistencySetupExecutor = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10), r -> {
                Thread t = new Thread(r, "reasoner-save-setup-worker");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());

    private final ExecutorService reasonerBuildExecutor = new ThreadPoolExecutor(4, 4, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(20), r -> {
                Thread t = new Thread(r, "reasoner-build-worker");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());

    /**
     * Create or get cached reasoner for an ontology
     */
    public OWLReasoner getReasoner(OWLOntology ontology, ReasonerType type) {
        return obtainReasoner(ontology, type, Warmup.ALL);
    }

    public OWLReasoner getClassHierarchyReasoner(OWLOntology ontology, ReasonerType type) {
        return obtainReasoner(ontology, type, Warmup.CLASS_HIERARCHY);
    }

    public OWLReasoner getUnwarmedReasoner(OWLOntology ontology, ReasonerType type) {
        return obtainReasoner(ontology, type, Warmup.NONE);
    }

    private OWLReasoner obtainReasoner(OWLOntology ontology, ReasonerType type, Warmup warmup) {
        String cacheKey = System.identityHashCode(ontology) + "-" + type.name();

        CompletableFuture<OWLReasoner> future = reasonerCache.get(cacheKey,
                k -> submitReasonerBuild(ontology, type, warmup));
        OWLReasoner reasoner;
        try {
            reasoner = future.get(reasonerOperationTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            reasonerCache.asMap().remove(cacheKey, future);
            throw new RuntimeException(type.getDisplayName() + " reasoning timed out after "
                    + (reasonerOperationTimeoutMs / 1000) + "s — the ontology may be too large or complex "
                    + "for this reasoner. Try a different reasoner or a smaller scope.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(type.getDisplayName() + " reasoning was interrupted", e);
        } catch (Exception e) {
            reasonerCache.asMap().remove(cacheKey, future);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause instanceof RuntimeException re ? re : new RuntimeException(cause);
        }

        if (warmup == Warmup.ALL && partialWarmupBuilds.remove(future)) {
            precomputeAll(reasoner, type);
        }

        try {
            reasoner.flush();
        } catch (Exception e) {
            log.warn("Failed to flush reasoner: {}", e.getMessage());
        }
        return reasoner;
    }

    private CompletableFuture<OWLReasoner> submitReasonerBuild(OWLOntology ontology, ReasonerType type,
                                                               Warmup warmup) {
        CompletableFuture<OWLReasoner> future = new CompletableFuture<>();
        if (warmup != Warmup.ALL) {
            partialWarmupBuilds.add(future);
        }
        try {
            reasonerBuildExecutor.execute(() -> {
                try {
                    future.complete(switch (warmup) {
                        case ALL -> buildReasonerWithPrecompute(ontology, type);
                        case CLASS_HIERARCHY -> buildClassHierarchyReasoner(ontology, type);
                        case NONE -> createReasoner(ontology, type);
                    });
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(
                    new RuntimeException("Too many reasoning requests in progress — please try again shortly."));
        }
        return future;
    }

    private OWLReasoner buildReasonerWithPrecompute(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = createReasoner(ontology, type);
        log.info("Precomputing inferences for new {} reasoner", type.getDisplayName());
        precomputeAll(reasoner, type);
        return reasoner;
    }

    private OWLReasoner buildClassHierarchyReasoner(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = createReasoner(ontology, type);
        log.info("Precomputing the class hierarchy for new {} reasoner", type.getDisplayName());
        try {
            reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
        } catch (Exception e) {
            log.warn("Failed to precompute the class hierarchy, some results might be incomplete: {}", e.getMessage());
        }
        return reasoner;
    }

    private void precomputeAll(OWLReasoner reasoner, ReasonerType type) {
        try {
            if (type == ReasonerType.ELK) {
                // ELK only supports EL profile inferences
                reasoner.precomputeInferences(
                    InferenceType.CLASS_HIERARCHY,
                    InferenceType.CLASS_ASSERTIONS
                );
            } else {
                reasoner.precomputeInferences(
                    InferenceType.CLASS_HIERARCHY,
                    InferenceType.OBJECT_PROPERTY_HIERARCHY,
                    InferenceType.DATA_PROPERTY_HIERARCHY,
                    InferenceType.CLASS_ASSERTIONS
                );
            }
        } catch (Exception e) {
            log.warn("Failed to precompute inferences, some results might be incomplete: {}", e.getMessage());
        }
    }

    /**
     * Create a new reasoner instance
     */
    private OWLReasoner createReasoner(OWLOntology ontology, ReasonerType type) {
        log.info("Creating {} reasoner for ontology: {}",
            type.getDisplayName(),
            ontology.getOntologyID().getOntologyIRI().orElse(null));

        OWLReasonerConfiguration config = new SimpleConfiguration(reasonerOperationTimeoutMs);

        try {
            return getReasonerFactory(type).createReasoner(ontology, config);
        } catch (Throwable e) {
            log.error("Failed to create {} reasoner", type, e);
            throw new RuntimeException(type.getDisplayName() + " reasoner could not be started: " + e.getMessage(), e);
        }
    }

    private OWLReasonerFactory getReasonerFactory(ReasonerType type) {
        switch (type) {
            case HERMIT:
                return new ReasonerFactory();

            case PELLET:
            case OPENLLET:
                return OpenlletReasonerFactory.getInstance();

            case FACTPLUSPLUS:
                return new JFactFactory();

            case ELK:
                return new ElkReasonerFactory();

            case STRUCTURAL:
            default:
                return new StructuralReasonerFactory();
        }
    }

    /**
     * Check ontology consistency
     */
    public boolean isConsistent(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        
        try {
            boolean consistent = reasoner.isConsistent();
            log.info("Consistency check ({}): {}", type.getDisplayName(), consistent);
            return consistent;
        } catch (Exception e) {
            log.error("Error checking consistency", e);
            throw new RuntimeException("Consistency check failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get all unsatisfiable classes (inconsistent classes)
     */
    public Set<OWLClass> getUnsatisfiableClasses(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        
        try {
            Node<OWLClass> bottomNode = reasoner.getUnsatisfiableClasses();
            Set<OWLClass> unsatisfiable = bottomNode.getEntities();
            
            // Remove owl:Nothing from results
            OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
            unsatisfiable.remove(df.getOWLNothing());
            
            log.info("Found {} unsatisfiable classes", unsatisfiable.size());
            return unsatisfiable;
        } catch (Exception e) {
            log.error("Error finding unsatisfiable classes", e);
            throw new RuntimeException("Failed to find unsatisfiable classes: " + e.getMessage(), e);
        }
    }

    /**
     * Classify the ontology (compute class hierarchy)
     */
    public void classify(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        
        try {
            log.info("Starting classification with {}", type.getDisplayName());
            long startTime = System.currentTimeMillis();
            
            if (type == ReasonerType.ELK) {
                reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
            } else {
                reasoner.precomputeInferences(
                    InferenceType.CLASS_HIERARCHY,
                    InferenceType.OBJECT_PROPERTY_HIERARCHY,
                    InferenceType.DATA_PROPERTY_HIERARCHY
                );
            }
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("Classification completed in {} ms", duration);
        } catch (Exception e) {
            log.error("Error during classification", e);
            throw new RuntimeException("Classification failed: " + e.getMessage(), e);
        }
    }

    /**
     * Realize the ontology (compute instances for all classes)
     */
    public void realize(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        
        try {
            log.info("Starting realization with {}", type.getDisplayName());
            long startTime = System.currentTimeMillis();
            
            reasoner.precomputeInferences(InferenceType.CLASS_ASSERTIONS);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("Realization completed in {} ms", duration);
        } catch (Exception e) {
            log.error("Error during realization", e);
            throw new RuntimeException("Realization failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get inferred superclasses for a class
     */
    public Set<OWLClass> getInferredSuperClasses(OWLOntology ontology, OWLClass owlClass, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        
        try {
            NodeSet<OWLClass> superClasses = reasoner.getSuperClasses(owlClass, false);
            return superClasses.getFlattened();
        } catch (Exception e) {
            log.error("Error getting inferred superclasses", e);
            return Collections.emptySet();
        }
    }

    /**
     * Get inferred subclasses for a class
     */
    public Set<OWLClass> getInferredSubClasses(OWLOntology ontology, OWLClass owlClass, ReasonerType type, boolean direct) {
        return getInferredSubClasses(getReasoner(ontology, type), owlClass, direct);
    }

    public Set<OWLClass> getInferredSubClasses(OWLReasoner reasoner, OWLClass owlClass, boolean direct) {
        try {
            NodeSet<OWLClass> subClasses = reasoner.getSubClasses(owlClass, direct);
            return subClasses.getNodes().stream()
                .filter(n -> !n.isBottomNode())
                .flatMap(n -> n.getEntities().stream())
                .collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("Error getting inferred subclasses", e);
            return Collections.emptySet();
        }
    }

    public Set<OWLClass> getInferredSubClasses(OWLOntology ontology, OWLClass owlClass, ReasonerType type) {
        return getInferredSubClasses(ontology, owlClass, type, false);
    }

    /**
     * Get inferred instances for a class
     */
    public Set<OWLNamedIndividual> getInferredInstances(OWLOntology ontology, OWLClass owlClass, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        
        try {
            NodeSet<OWLNamedIndividual> individuals = reasoner.getInstances(owlClass, false);
            return individuals.getFlattened();
        } catch (Exception e) {
            log.error("Error getting inferred instances", e);
            return Collections.emptySet();
        }
    }

    /**
     * Get inferred types for an individual
     */
    public Set<OWLClass> getInferredTypes(OWLOntology ontology, OWLNamedIndividual individual, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);

        CompletableFuture<NodeSet<OWLClass>> future = CompletableFuture.supplyAsync(
            () -> reasoner.getTypes(individual, true), inferredTypesExecutor);
        try {
            NodeSet<OWLClass> types = future.get(PER_INDIVIDUAL_TYPE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return types.getFlattened();
        } catch (TimeoutException te) {
            log.warn("Timed out computing inferred types for individual {} ({} classes in signature)",
                individual.getIRI(), ontology.getClassesInSignature().size());
            // Protege's pattern (OWLReasonerManagerImpl#killCurrentClassification + its
            // ReasonerInterruptedException handler): interrupt() so HermiT actually stops
            // instead of burning CPU in the background, then treat the reasoner as unusable
            // — dispose it once the abandoned call unwinds, rather than reusing it from cache.
            reasoner.interrupt();
            future.whenComplete((result, ex) -> disposeReasoner(ontology, type));
            return Collections.emptySet();
        } catch (Exception e) {
            log.error("Error getting inferred types", e);
            return Collections.emptySet();
        }
    }

    /**
     * Get inferred sub object properties for a property
     */
    public Set<OWLObjectPropertyExpression> getInferredSubObjectProperties(OWLOntology ontology, OWLObjectProperty property, ReasonerType type, boolean direct) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        try {
            NodeSet<OWLObjectPropertyExpression> subProps = reasoner.getSubObjectProperties(property, direct);
            Set<OWLObjectPropertyExpression> result = subProps.getFlattened();
            if (property.isOWLTopObjectProperty()) {
                log.debug("Found {} sub-properties of owl:topObjectProperty (direct={})", result.size(), direct);
            }
            return result;
        } catch (Exception e) {
            log.error("Error getting inferred sub object properties for {}", property.getIRI(), e);
            return Collections.emptySet();
        }
    }

    public Set<OWLObjectPropertyExpression> getInferredSubObjectProperties(OWLOntology ontology, OWLObjectProperty property, ReasonerType type) {
        return getInferredSubObjectProperties(ontology, property, type, false);
    }

    /**
     * Get inferred sub data properties for a property
     */
    public Set<OWLDataPropertyExpression> getInferredSubDataProperties(OWLOntology ontology, OWLDataProperty property, ReasonerType type, boolean direct) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        try {
            NodeSet<OWLDataProperty> subProps = reasoner.getSubDataProperties(property, direct);
            Set<OWLDataPropertyExpression> result = new HashSet<>(subProps.getFlattened());
            if (property.isOWLTopDataProperty()) {
                log.debug("Found {} sub-properties of owl:topDataProperty (direct={})", result.size(), direct);
            }
            return result;
        } catch (Exception e) {
            log.error("Error getting inferred sub data properties for {}", property.getIRI(), e);
            return Collections.emptySet();
        }
    }

    public Set<OWLDataPropertyExpression> getInferredSubDataProperties(OWLOntology ontology, OWLDataProperty property, ReasonerType type) {
        return getInferredSubDataProperties(ontology, property, type, false);
    }

    /**
     * Get all inferred axioms
     */
    public Set<OWLAxiom> getInferredAxioms(OWLOntology ontology, ReasonerType type) {
        Set<OWLAxiom> inferredAxioms = new HashSet<>();
        
        try {
            log.info("Generating inferred axioms");
            
            // Get all classes in the ontology
            for (OWLClass owlClass : ontology.getClassesInSignature()) {
                if (owlClass.isOWLThing() || owlClass.isOWLNothing()) {
                    continue;
                }
                
                // Inferred superclass axioms
                Set<OWLClass> superClasses = getInferredSuperClasses(ontology, owlClass, type);
                for (OWLClass superClass : superClasses) {
                    if (!superClass.isOWLThing()) {
                        OWLAxiom axiom = ontology.getOWLOntologyManager()
                            .getOWLDataFactory()
                            .getOWLSubClassOfAxiom(owlClass, superClass);
                        inferredAxioms.add(axiom);
                    }
                }
                
                // Inferred instance axioms
                Set<OWLNamedIndividual> instances = getInferredInstances(ontology, owlClass, type);
                for (OWLNamedIndividual individual : instances) {
                    OWLAxiom axiom = ontology.getOWLOntologyManager()
                        .getOWLDataFactory()
                        .getOWLClassAssertionAxiom(owlClass, individual);
                    inferredAxioms.add(axiom);
                }
            }
            
            log.info("Generated {} inferred axioms", inferredAxioms.size());
            return inferredAxioms;
            
        } catch (Exception e) {
            log.error("Error generating inferred axioms", e);
            return Collections.emptySet();
        }
    }

    /**
     * Explain why a class is unsatisfiable
     */
    public Set<OWLAxiom> explainUnsatisfiability(OWLOntology ontology, OWLClass owlClass, ReasonerType type) {
        try {
            Set<OWLClassAxiom> explanation = ontology.getAxioms(owlClass);
            log.info("Found {} axioms in explanation for {}", explanation.size(), owlClass.getIRI());
            return new HashSet<>(explanation);
            
        } catch (Exception e) {
            log.error("Error explaining unsatisfiability", e);
            return Collections.emptySet();
        }
    }

    /**
     * Dispose all cached reasoners for an ontology and remove it from its manager.
     * Called when the editor ontology cache evicts after idle timeout.
     */
    public void releaseOntologyFromMemory(OWLOntology ontology) {
        if (ontology == null) {
            return;
        }
        for (ReasonerType type : ReasonerType.values()) {
            disposeReasoner(ontology, type);
        }
        try {
            ontology.getOWLOntologyManager().removeOntology(ontology);
        } catch (Exception e) {
            log.debug("Ontology remove after cache eviction: {}", e.getMessage());
        }
    }

    /**
     * Clear reasoner cache — invalidateAll triggers the removalListener which disposes each reasoner.
     */
    public void clearCache() {
        log.info("Clearing reasoner cache ({} entries)", reasonerCache.estimatedSize());
        reasonerCache.invalidateAll();
    }

    /**
     * Returns a warmed classification reasoner for this ontology, if one exists.
     * Does not create a new reasoner (unlike {@link #getReasoner}).
     */
    public Optional<OWLReasoner> findCachedReasoner(OWLOntology ontology) {
        for (ReasonerType type : ReasonerType.values()) {
            String cacheKey = System.identityHashCode(ontology) + "-" + type.name();
            CompletableFuture<OWLReasoner> future = reasonerCache.getIfPresent(cacheKey);
            if (future != null && future.isDone() && !future.isCompletedExceptionally()) {
                return Optional.of(future.join());
            }
        }
        return Optional.empty();
    }

    /**
     * Dispose reasoner for specific ontology object
     */
    public void disposeReasoner(OWLOntology ontology, ReasonerType type) {
        String cacheKey = System.identityHashCode(ontology) + "-" + type.name();
        // invalidate triggers removalListener which calls dispose()
        reasonerCache.invalidate(cacheKey);
        log.info("Disposed {} reasoner for ontology object {}", type.getDisplayName(), System.identityHashCode(ontology));
    }

    /**
     * Dispose reasoner for specific ontology ID string
     */
    public void disposeReasoner(String ontologyId, ReasonerType type) {
        String cacheKey = ontologyId + "-" + type.name();
        reasonerCache.invalidate(cacheKey);
        log.info("Disposed {} reasoner for ontology {}", type.getDisplayName(), ontologyId);
    }

    /**
     * Get reasoner statistics
     */
    public Map<String, Object> getReasonerStats(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        Map<String, Object> stats = new HashMap<>();
        
        try {
            stats.put("reasonerType", type.getDisplayName());
            stats.put("reasonerName", reasoner.getReasonerName());
            stats.put("reasonerVersion", reasoner.getReasonerVersion().toString());
            
            // Consistency check
            boolean isConsistent = reasoner.isConsistent();
            stats.put("isConsistent", isConsistent);
            
            // Entity counts
            int classCount = ontology.getClassesInSignature().size();
            int objectPropertyCount = ontology.getObjectPropertiesInSignature().size();
            int dataPropertyCount = ontology.getDataPropertiesInSignature().size();
            int individualCount = ontology.getIndividualsInSignature().size();
            
            stats.put("classCount", classCount);
            stats.put("objectPropertyCount", objectPropertyCount);
            stats.put("dataPropertyCount", dataPropertyCount);
            stats.put("propertyCount", objectPropertyCount + dataPropertyCount);
            stats.put("individualCount", individualCount);
            
            // Unsatisfiable classes
            if (isConsistent) {
                Node<OWLClass> bottomNode = reasoner.getUnsatisfiableClasses();
                int unsatisfiableCount = bottomNode.getSize() - 1; // Exclude owl:Nothing
                stats.put("unsatisfiableClasses", Math.max(0, unsatisfiableCount));
                stats.put("satisfiableClasses", classCount - Math.max(0, unsatisfiableCount));
            } else {
                stats.put("unsatisfiableClasses", -1);
                stats.put("satisfiableClasses", 0);
            }
            
            // Axiom counts
            stats.put("logicalAxiomCount", ontology.getLogicalAxiomCount());
            stats.put("totalAxiomCount", ontology.getAxiomCount());
            
            // Reasoner capabilities
            // Incremental reasoning support is not directly available via OWLReasoner API
            stats.put("supportsIncrementalReasoning", false);
            stats.put("supportsDatatypeReasoning", type != ReasonerType.STRUCTURAL);
            stats.put("supportsOWL2DL", type == ReasonerType.HERMIT || type == ReasonerType.PELLET);
            
            // Inferred axioms estimate — getInferredAxioms() runs a per-class superclass and
            // instance query against the reasoner, which is O(classCount) work. Fine for a
            // handful of classes, but prohibitively slow as part of a "stats overview" call
            // once the ontology gets large, so skip it past a sane threshold.
            if (classCount <= INFERRED_AXIOM_COUNT_MAX_CLASSES) {
                try {
                    Set<OWLAxiom> inferredAxioms = getInferredAxioms(ontology, type);
                    stats.put("inferredAxioms", inferredAxioms.size());
                } catch (Exception e) {
                    stats.put("inferredAxioms", 0);
                }
            } else {
                stats.put("inferredAxioms", -1);
            }
            
        } catch (Exception e) {
            log.error("Error getting reasoner stats", e);
            stats.put("error", e.getMessage());
        }

        return stats;
    }

    private static final String GENERIC_INCONSISTENCY_MESSAGE =
        "Inconsistent: this change makes the ontology inconsistent.";

    public static final class SaveConsistencyResult {
        public final boolean consistent;
        public final boolean timedOut;
        public final String violationMessage;
         public final String entity;

        public final List<String> unsatisfiableClasses;

        private SaveConsistencyResult(boolean consistent, boolean timedOut, String violationMessage, String entity,
                List<String> unsatisfiableClasses) {
            this.consistent = consistent;
            this.timedOut = timedOut;
            this.violationMessage = violationMessage;
            this.entity = entity;
            this.unsatisfiableClasses = unsatisfiableClasses;
        }

        static SaveConsistencyResult consistent(List<String> unsatisfiableClasses) {
            return new SaveConsistencyResult(true, false, null, null, unsatisfiableClasses);
        }

        static SaveConsistencyResult timedOut() {
            return new SaveConsistencyResult(true, true, null, null, Collections.emptyList());
        }

        static SaveConsistencyResult inconsistent(String message, String entity) {
            return new SaveConsistencyResult(false, false, message, entity, Collections.emptyList());
        }
    }

    public SaveConsistencyResult checkConsistencyForSave(OWLOntology ontology, ReasonerType type) {

        SaveConsistencyResult illTyped = findIllTypedLiteralViolation(ontology);
        if (illTyped != null) {
            return illTyped;
        }

        long setupStart = System.currentTimeMillis();
        CompletableFuture<OWLReasoner> setup;
        try {
            setup = CompletableFuture.supplyAsync(() -> createReasoner(stripSwrlRules(ontology), type),
                    saveConsistencySetupExecutor);
        } catch (RejectedExecutionException ree) {
            log.warn("Too many concurrent save-time reasoner setups; allowing save through unchecked");
            return SaveConsistencyResult.timedOut();
        }
        OWLReasoner reasoner;
        try {
            reasoner = setup.get(SAVE_CONSISTENCY_SETUP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("Save-time setup (strip+create) took longer than {} ms; allowing save through unchecked",
                    SAVE_CONSISTENCY_SETUP_TIMEOUT_MS);
            setup.thenAccept(r -> { try { r.dispose(); } catch (Exception ignored) {} });
            return SaveConsistencyResult.timedOut();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            setup.thenAccept(r -> { try { r.dispose(); } catch (Exception ignored) {} });
            return SaveConsistencyResult.timedOut();
        } catch (Exception e) {
            log.warn("Save-time setup failed ({}); allowing save through unchecked", e.getMessage());
            return SaveConsistencyResult.timedOut();
        }
        long consistencyStart = System.currentTimeMillis();
        CompletableFuture<Boolean> future;
        try {
            future = CompletableFuture.supplyAsync(reasoner::isConsistent, saveConsistencyExecutor);
        } catch (RejectedExecutionException ree) {
            log.warn("Too many concurrent save-time consistency checks; allowing save through unchecked");
            try { reasoner.dispose(); } catch (Exception ignored) {}
            return SaveConsistencyResult.timedOut();
        }

        boolean consistent;
        try {
            consistent = future.get(SAVE_CONSISTENCY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            log.info("[TIMING] Save-time reasoning: strip+create {} ms, isConsistent() {} ms",
                    consistencyStart - setupStart, System.currentTimeMillis() - consistencyStart);
        } catch (TimeoutException te) {
            log.warn("Save-time consistency check timed out after {} ms; allowing save through unchecked",
                    SAVE_CONSISTENCY_TIMEOUT_MS);
            reasoner.interrupt();
            future.whenComplete((r, ex) -> { try { reasoner.dispose(); } catch (Exception ignored) {} });
            return SaveConsistencyResult.timedOut();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            reasoner.interrupt();
            future.whenComplete((r, ex) -> { try { reasoner.dispose(); } catch (Exception ignored) {} });
            return SaveConsistencyResult.timedOut();
        } catch (Exception e) {
            log.warn("Save-time consistency check failed ({}); allowing save through unchecked", e.getMessage());
            try { reasoner.dispose(); } catch (Exception ignored) {}
            return SaveConsistencyResult.timedOut();
        }

        if (consistent) {

            try { reasoner.dispose(); } catch (Exception ignored) {}
            return SaveConsistencyResult.consistent(Collections.emptyList());
        }

        try { reasoner.dispose(); } catch (Exception ignored) {}
     
        return SaveConsistencyResult.inconsistent(GENERIC_INCONSISTENCY_MESSAGE, null);
    }

    public record QuickUnsatResult(List<String> unsatisfiableClasses, boolean inconsistent) {}

    public QuickUnsatResult getUnsatisfiableClassesQuick(OWLOntology ontology, ReasonerType type,
                                                         java.util.function.Consumer<OWLReasoner> onReasonerCreated) {
        return getUnsatisfiableClassesQuickWithoutSwrl(stripSwrlRules(ontology), type, onReasonerCreated);
    }

    public OWLOntology withoutSwrlRules(OWLOntology ontology) {
        return stripSwrlRules(ontology);
    }

    public QuickUnsatResult getUnsatisfiableClassesQuickWithoutSwrl(OWLOntology reasoningOntology, ReasonerType type,
                                                                    java.util.function.Consumer<OWLReasoner> onReasonerCreated) {
        OWLReasoner reasoner = createReasoner(reasoningOntology, type);
        try {
            onReasonerCreated.accept(reasoner);
            if (!reasoner.isConsistent()) {
                return new QuickUnsatResult(Collections.emptyList(), true);
            }
            Node<OWLClass> bottomNode = reasoner.getUnsatisfiableClasses();
            OWLDataFactory df = reasoningOntology.getOWLOntologyManager().getOWLDataFactory();
            List<String> unsatisfiable = bottomNode.getEntities().stream()
                .filter(c -> !c.equals(df.getOWLNothing()))
                .map(c -> c.getIRI().toString())
                .collect(Collectors.toList());
            return new QuickUnsatResult(unsatisfiable, false);
        } finally {
            try { reasoner.dispose(); } catch (Exception ignored) {}
        }
    }


    private SaveConsistencyResult findIllTypedLiteralViolation(OWLOntology ontology) {
        for (OWLDataPropertyAssertionAxiom axiom : ontology.getAxioms(AxiomType.DATA_PROPERTY_ASSERTION)) {
            OWLLiteral literal = axiom.getObject();
            IRI datatypeIri = literal.getDatatype().getIRI();
            if (!org.semanticweb.owlapi.vocab.OWL2Datatype.isBuiltIn(datatypeIri)) {
                continue;
            }
            org.semanticweb.owlapi.vocab.OWL2Datatype datatype = org.semanticweb.owlapi.vocab.OWL2Datatype.getDatatype(datatypeIri);
            if (!datatype.isInLexicalSpace(literal.getLiteral())) {
                String entity = axiom.getSubject().isNamed()
                    ? axiom.getSubject().asOWLNamedIndividual().getIRI().toString().replaceAll("^.*[#/]", "")
                    : null;
                return SaveConsistencyResult.inconsistent(GENERIC_INCONSISTENCY_MESSAGE, entity);
            }
        }
        return null;
    }
 private OWLOntology stripSwrlRules(OWLOntology ontology) {
        Set<OWLAxiom> axioms = ontology.getAxioms().stream()
            .filter(ax -> ax.getAxiomType() != AxiomType.SWRL_RULE)
            .collect(Collectors.toSet());
        try {
            OWLOntologyManager mgr = OWLManager.createOWLOntologyManager();
            return mgr.createOntology(axioms);
        } catch (OWLOntologyCreationException e) {
            log.warn("Failed to strip SWRL rules, falling back to original ontology", e);
            return ontology;
        }
    }

}
