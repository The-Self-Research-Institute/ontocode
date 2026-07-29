package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.*;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Service for ontology reasoning operations.
 * Supports multiple reasoners: HermiT, Pellet (Openllet), FaCT++, ELK, and Structural.
 */
@Service("owlEditorReasonerService")
public class ReasonerService {

    private static final Logger log = LoggerFactory.getLogger(ReasonerService.class);

    // Caffeine cache: max 5 reasoners, evict after 30 min idle, dispose on removal
    // (each reasoner holds the full ontology + inference state in heap)
    private final Cache<String, OWLReasoner> reasonerCache = Caffeine.newBuilder()
        .maximumSize(5)
        .expireAfterAccess(15, TimeUnit.MINUTES)
        .removalListener((key, value, cause) -> {
            if (value instanceof OWLReasoner reasoner) {
                log.info("[ReasonerCache] Disposing reasoner: {} (cause={})", key, cause);
                try { reasoner.dispose(); } catch (Exception ignored) {}
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
    private final ExecutorService inferredTypesExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "reasoner-inferred-types-worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * Create or get cached reasoner for an ontology
     */
    public OWLReasoner getReasoner(OWLOntology ontology, ReasonerType type) {
        String cacheKey = System.identityHashCode(ontology) + "-" + type.name();

        OWLReasoner cached = reasonerCache.getIfPresent(cacheKey);
        if (cached != null) {
            try { cached.flush(); } catch (Exception e) {
                log.warn("Failed to flush reasoner: {}", e.getMessage());
            }
            return cached;
        }

        OWLReasoner reasoner = createReasoner(ontology, type);
        log.info("Precomputing inferences for new {} reasoner", type.getDisplayName());
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
        } catch (Throwable e) {
            // Throwable: some reasoner libraries raise Errors (e.g. NoSuchMethodError) during
            // precompute rather than at creation — never let those crash the request.
            log.warn("Failed to precompute inferences, some results might be incomplete: {}", e.getMessage());
        }

        reasonerCache.put(cacheKey, reasoner);
        return reasoner;
    }

    /**
     * Create a new reasoner instance
     */
    private OWLReasoner createReasoner(OWLOntology ontology, ReasonerType type) {
        log.info("Creating {} reasoner for ontology: {}", 
            type.getDisplayName(), 
            ontology.getOntologyID().getOntologyIRI().orElse(null));

        OWLReasonerConfiguration config = new SimpleConfiguration();
        
        try {
            switch (type) {
                case HERMIT:
                    // HermiT - Hypertableau-based reasoner, best for complex ontologies
                    log.info("Using HermiT (Hypertableau) reasoner");
                    // Note: HermiT 1.4.3.456 has a binary compatibility issue with OWLAPI 5.5.0
                    // specifically regarding OWLOntologyID.getDefaultDocumentIRI()
                    // Falling back to Openllet if HermiT fails to initialize
                    try {
                        return new ReasonerFactory().createReasoner(ontology, config);
                    } catch (NoSuchMethodError e) {
                        log.error("HermiT binary compatibility error with OWLAPI 5.5.0: {}. Falling back to Openllet.", e.getMessage());
                        return OpenlletReasonerFactory.getInstance().createReasoner(ontology, config);
                    }
                    
                case PELLET:
                case OPENLLET:
                    // Use Openllet (OWLAPI 5.x compatible reasoner)
                    log.info("Using Pellet/Openllet reasoner");
                    return OpenlletReasonerFactory.getInstance().createReasoner(ontology, config);
                    
                case FACTPLUSPLUS:
                    log.info("Using FaCT++ (JFact) reasoner");
                    return new JFactFactory().createReasoner(ontology, config);

                case ELK:
                    // ELK: consequence-based OWL EL reasoner — 10-100x faster than HermiT,
                    // fraction of memory usage. Uses io.github.liveontologies:elk-owlapi:0.6.0
                    // (OWLAPI 5-compatible). Falls back to Structural if ELK can't handle the
                    // ontology (non-EL constructs) or hits a library error.
                    log.info("Using ELK reasoner (OWL EL profile)");
                    try {
                        return new ElkReasonerFactory().createReasoner(ontology, config);
                    } catch (Throwable e) {
                        log.warn("ELK failed (ontology may use non-EL constructs): {}. Falling back to Structural.", e.getMessage());
                        return new StructuralReasonerFactory().createReasoner(ontology, config);
                    }

                case STRUCTURAL:
                default:
                    log.info("Using Structural reasoner (basic)");
                    return new StructuralReasonerFactory().createReasoner(ontology, config);
            }
        } catch (Throwable e) {
            // Catch Throwable (not just Exception) so binary-incompatibility Errors such as
            // NoSuchMethodError from a reasoner library degrade to the Structural reasoner
            // instead of bubbling up to the controller and surfacing as a UI "Reasoner error".
            log.error("Failed to create {} reasoner, falling back to Structural", type, e);
            return new StructuralReasonerFactory().createReasoner(ontology, config);
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
        OWLReasoner reasoner = getReasoner(ontology, type);
        
        try {
            NodeSet<OWLClass> subClasses = reasoner.getSubClasses(owlClass, direct);
            return subClasses.getFlattened();
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
            OWLReasoner reasoner = reasonerCache.getIfPresent(cacheKey);
            if (reasoner != null) {
                return Optional.of(reasoner);
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
            
            // Inferred axioms estimate
            try {
                Set<OWLAxiom> inferredAxioms = getInferredAxioms(ontology, type);
                stats.put("inferredAxioms", inferredAxioms.size());
            } catch (Exception e) {
                stats.put("inferredAxioms", 0);
            }
            
        } catch (Exception e) {
            log.error("Error getting reasoner stats", e);
            stats.put("error", e.getMessage());
        }
        
        return stats;
    }
}
