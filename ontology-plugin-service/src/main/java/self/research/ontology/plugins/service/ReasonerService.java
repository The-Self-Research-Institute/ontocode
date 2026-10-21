package self.research.ontology.plugins.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.*;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import openllet.owlapi.OpenlletReasonerFactory;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import uk.ac.manchester.cs.jfact.JFactFactory;
import org.semanticweb.owl.explanation.api.Explanation;
import org.semanticweb.owl.explanation.api.ExplanationGenerator;
import org.semanticweb.owl.explanation.api.ExplanationGeneratorFactory;
import org.semanticweb.owl.explanation.api.ExplanationProgressMonitor;
import org.semanticweb.owl.explanation.impl.blackbox.Configuration;
import org.semanticweb.owl.explanation.impl.blackbox.ContractionStrategy;
import org.semanticweb.owl.explanation.impl.blackbox.DivideAndConquerContractionStrategy;
import org.semanticweb.owl.explanation.impl.blackbox.EntailmentCheckerFactory;
import org.semanticweb.owl.explanation.impl.blackbox.EntailmentChecker;
import org.semanticweb.owl.explanation.impl.blackbox.ExpansionStrategy;
import org.semanticweb.owl.explanation.impl.blackbox.InitialEntailmentCheckStrategy;
import org.semanticweb.owl.explanation.impl.blackbox.StructuralTypePriorityExpansionStrategy;
import org.semanticweb.owl.explanation.impl.laconic.LaconicExplanationGeneratorFactory;
import org.semanticweb.owl.explanation.impl.blackbox.checker.BlackBoxExplanationGeneratorFactory;
import org.semanticweb.owl.explanation.impl.blackbox.checker.InconsistentOntologyExplanationGeneratorFactory;
import org.semanticweb.owl.explanation.impl.blackbox.checker.SatisfiabilityEntailmentCheckerFactory;
import org.semanticweb.owl.explanation.impl.blackbox.checker.SatisfiabilityEntailmentChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.common.JustificationRendering;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Service for ontology reasoning operations.
 * Supports multiple reasoners: HermiT, Pellet (Openllet), FaCT++, ELK, and Structural.
 */
@Service("pluginReasonerService")
public class ReasonerService {

    private static final Logger log = LoggerFactory.getLogger(ReasonerService.class);

    // Shared data factory — independent of any per-ontology OWLOntologyManager.
    // OWLAPI 5.x OWLOntologyImpl holds its manager via a WeakReference; if that
    // manager is collected, ontology.getOWLOntologyManager() returns null. Using
    // a separate shared factory ensures basic axiom/entity creation never fails
    // due to a collected manager.
    private static final OWLDataFactory DATA_FACTORY =
            OWLManager.createOWLOntologyManager().getOWLDataFactory();

    // ConcurrentHashMap: read/written from request threads, the classify
    // executor, and the controller's LRU-eviction path concurrently.
    private final Map<String, CompletableFuture<OWLReasoner>> reasonerCache = new ConcurrentHashMap<>();

    private final Map<OWLOntology, Long> ontologyIdentitySequence = new IdentityHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong nextOntologyIdentitySequence = new java.util.concurrent.atomic.AtomicLong(1);

    private long identityKeyFor(OWLOntology ontology) {
        synchronized (ontologyIdentitySequence) {
            return ontologyIdentitySequence.computeIfAbsent(ontology, o -> nextOntologyIdentitySequence.getAndIncrement());
        }
    }

    // HermiT's InstanceManager.getTypes() does O(n) LinkedList scans internally,
    // which blows up to O(n^2) against the class count on large ontologies (e.g.
    // FoodOn's 39k+ classes) and can run for hours on a single individual. That
    // work is not cancellable once started, so we fire it on a daemon thread and
    // abandon it on timeout rather than block the request indefinitely.
    // Desktop is single-user/single-request, so it can afford a much longer budget
    // than a shared cloud deployment (see application-desktop.properties overrides).
    @Value("${ontocode.reasoner.per-individual-timeout-ms:5000}")
    private long PER_INDIVIDUAL_TYPE_TIMEOUT_MS;

    // Even when no single individual hangs, each getTypes() call still costs
    // O(classCount) against HermiT's InstanceManager — with hundreds of
    // individuals over a 39k-class hierarchy that's ~3s/individual, i.e. 20+
    // minutes in aggregate. Cap the whole loop, not just one call.
    @Value("${ontocode.reasoner.realization-budget-ms:20000}")
    private long REALIZATION_TOTAL_BUDGET_MS;
    private final ExecutorService realizationExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "reasoner-realization-worker");
        t.setDaemon(true);
        return t;
    });

    @Value("${ontocode.reasoner.justification-pool-size:2}")
    private int justificationPoolSize;
    private ExecutorService justificationExecutor;

    @jakarta.annotation.PostConstruct
    private void initJustificationExecutor() {
        justificationExecutor = new java.util.concurrent.ThreadPoolExecutor(
                justificationPoolSize, justificationPoolSize, 60, TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(20), r -> {
                    Thread t = new Thread(r, "reasoner-justification-worker");
                    t.setDaemon(true);
                    return t;
                }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    @Value("${ontocode.reasoner.justification-timeout-ms:55000}")
    private long JUSTIFICATION_TIMEOUT_MS;
    @Value("${ontocode.reasoner.justification-queue-wait-ms:30000}")
    private long JUSTIFICATION_QUEUE_WAIT_MS;
    @Value("${ontocode.reasoner.max-justifications:5}")
    private int MAX_JUSTIFICATIONS;
    @Value("${ontocode.reasoner.operation-timeout-ms:45000}")
    private long reasonerOperationTimeoutMs;
    private final ExecutorService reasonerBuildExecutor = new java.util.concurrent.ThreadPoolExecutor(
            4, 4, 60, TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(20), r -> {
                Thread t = new Thread(r, "reasoner-build-worker");
                t.setDaemon(true);
                return t;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    /**
     * Create or get cached reasoner for an ontology
     */
    public OWLReasoner getReasoner(OWLOntology ontology, ReasonerType type) {
        String cacheKey = identityKeyFor(ontology) + "-" + ontology.getOntologyID() + "-" + type.name();
        CompletableFuture<OWLReasoner> future = reasonerCache.computeIfAbsent(cacheKey, k -> submitReasonerBuild(ontology, type));
        try {
            return future.get(reasonerOperationTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (reasonerCache.remove(cacheKey, future)) {
                disposeWhenBuilt(future);
            }
            throw new RuntimeException(type.getDisplayName() + " reasoning timed out after "
                    + (reasonerOperationTimeoutMs / 1000) + "s — the ontology may be too large or complex "
                    + "for this reasoner. Try a different reasoner or a smaller scope.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(type.getDisplayName() + " reasoning was interrupted", e);
        } catch (Exception e) {
            reasonerCache.remove(cacheKey, future);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause instanceof RuntimeException re ? re : new RuntimeException(cause);
        }
    }

    private static void disposeWhenBuilt(CompletableFuture<OWLReasoner> future) {
        future.thenAccept(reasoner -> {
            try {
                if (reasoner != null) {
                    reasoner.dispose();
                }
            } catch (Exception e) {
                log.warn("Error disposing abandoned reasoner", e);
            }
        });
    }

    private CompletableFuture<OWLReasoner> submitReasonerBuild(OWLOntology ontology, ReasonerType type) {
        CompletableFuture<OWLReasoner> future = new CompletableFuture<>();
        try {
            reasonerBuildExecutor.execute(() -> {
                try {
                    future.complete(createReasoner(ontology, type));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            future.completeExceptionally(
                    new RuntimeException("Too many reasoning requests in progress — please try again shortly."));
        }
        return future;
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
                // HermiT - Hypertableau-based reasoner, best for complex ontologies
                return new ReasonerFactory();

            case PELLET:
            case OPENLLET:
                // Use Openllet (OWLAPI 5.x compatible reasoner)
                return OpenlletReasonerFactory.getInstance();

            case FACTPLUSPLUS:
                // FaCT++ (via JFact - Java port)
                return new JFactFactory();

            case ELK:
                // ELK - Fast EL++ reasoner; best choice for large biomedical ontologies
                // (MONDO, GO, HP, etc.) that conform to the OWL EL profile.
                // Note: ELK only supports EL profile of OWL 2, not full OWL 2 DL.
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
            // Check consistency first
            if (!reasoner.isConsistent()) {
                log.warn("Ontology is inconsistent, returning empty set for unsatisfiable classes");
                return Collections.emptySet();
            }
            
            Node<OWLClass> bottomNode = reasoner.getUnsatisfiableClasses();
            Set<OWLClass> unsatisfiable = bottomNode.getEntities();

            // Remove owl:Nothing from results
            unsatisfiable.remove(DATA_FACTORY.getOWLNothing());
            
            log.info("Found {} unsatisfiable classes", unsatisfiable.size());
            return unsatisfiable;
        } catch (Exception e) {
            log.error("Error finding unsatisfiable classes", e);
            return Collections.emptySet();
        }
    }

    /**
     * Classify the ontology (compute class hierarchy)
     */
    public void classify(OWLOntology ontology, ReasonerType type) {
        classify(getReasoner(ontology, type), ontology, type);
    }


    public void classify(OWLReasoner reasoner, OWLOntology ontology, ReasonerType type) {
        try {
            log.info("Starting classification with {}", type.getDisplayName());
            long startTime = System.currentTimeMillis();
            
            // Check consistency first - if inconsistent, classification may fail
            boolean isConsistent = reasoner.isConsistent();
            if (!isConsistent) {
                log.warn("Ontology is inconsistent. Classification may not produce meaningful results.");
                // Continue anyway - some reasoners can still provide partial results
            }
            
            // ELK has specific limitations - only precompute supported types
            if (type == ReasonerType.ELK) {
                log.info("ELK reasoner: precomputing EL-profile compatible inferences");
                try {
                    reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
                } catch (Exception e) {
                    log.warn("ELK: CLASS_HIERARCHY precomputation failed, trying basic classification", e);
                    // Some ELK versions might have issues, continue anyway
                }
            } else {
                // Precompute each inference type separately so a failure in property hierarchy
                // (e.g. NPE on malformed OWLObjectPropertyRangeAxiom) doesn't abort class classification.
                try {
                    reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
                } catch (Exception e) {
                    log.warn("{}: CLASS_HIERARCHY precomputation failed: {}", type.getDisplayName(), e.getMessage());
                }
                try {
                    reasoner.precomputeInferences(InferenceType.OBJECT_PROPERTY_HIERARCHY);
                } catch (Exception e) {
                    log.warn("{}: OBJECT_PROPERTY_HIERARCHY precomputation failed (will use asserted): {}", type.getDisplayName(), e.getMessage());
                }
                try {
                    reasoner.precomputeInferences(InferenceType.DATA_PROPERTY_HIERARCHY);
                } catch (Exception e) {
                    log.warn("{}: DATA_PROPERTY_HIERARCHY precomputation failed (will use asserted): {}", type.getDisplayName(), e.getMessage());
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("Classification completed in {} ms", duration);
        } catch (Exception e) {
            // Catches failures from isConsistent() or any other unexpected path.
            // Individual precompute failures are already handled inline above.
            log.warn("Unexpected error during classification setup with {}: {} — continuing with partial results",
                type.getDisplayName(), e.getMessage());
        }
    }

    /**
     * Get classification results including class hierarchy, equivalent classes, and unsatisfiable classes
     */
    public Map<String, Object> getClassificationResults(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        Map<String, Object> results = new HashMap<>();
        
        try {
            OWLClass owlThing = DATA_FACTORY.getOWLThing();
            
            // Check consistency first
            boolean isConsistent = true;
            try {
                isConsistent = reasoner.isConsistent();
                results.put("isConsistent", isConsistent);
                if (!isConsistent) {
                    log.warn("Ontology is inconsistent - providing limited classification results");
                }
            } catch (Exception e) {
                log.error("Error checking consistency", e);
                results.put("isConsistent", false);
                isConsistent = false;
            }
            
            // Build class hierarchy (even for inconsistent ontologies, provide what we can)
            List<Map<String, Object>> classHierarchy = new ArrayList<>();
            try {
                Set<OWLClass> processedClasses = new HashSet<>();
                buildClassHierarchy(reasoner, ontology, owlThing, classHierarchy, processedClasses, 0);
            } catch (Exception e) {
                if (type == ReasonerType.ELK) {
                    log.warn("ELK: Error building class hierarchy - may be due to unsupported OWL constructs", e);
                } else {
                    log.error("Error building class hierarchy, returning empty", e);
                }
            }
            results.put("classHierarchy", classHierarchy);
            
            // Find equivalent classes
            List<Map<String, Object>> equivalentClasses = new ArrayList<>();
            Set<OWLClass> processedEquivalents = new HashSet<>();
            try {
                for (OWLClass cls : ontology.getClassesInSignature()) {
                    try {
                        if (processedEquivalents.contains(cls)) {
                            continue;
                        }
                        Set<OWLClass> equivalents = reasoner.getEquivalentClasses(cls).getEntities();
                        if (equivalents.size() > 1) {
                            processedEquivalents.addAll(equivalents);
                            Map<String, Object> eqGroup = new HashMap<>();
                            eqGroup.put("classes", equivalents.stream()
                                .map(c -> Map.of(
                                    "iri", c.getIRI().toString(),
                                    "label", getLabel(c, ontology)
                                ))
                                .collect(Collectors.toList()));
                            equivalentClasses.add(eqGroup);
                        }
                    } catch (Exception e) {
                        // For ELK, skip classes that cause issues
                        if (type != ReasonerType.ELK) {
                            log.debug("Error processing equivalent classes for {}", cls.getIRI(), e);
                        }
                    }
                }
            } catch (Exception e) {
                if (type == ReasonerType.ELK) {
                    log.warn("ELK: Error finding equivalent classes - may be due to unsupported OWL constructs", e);
                } else {
                    log.error("Error finding equivalent classes, returning empty", e);
                }
            }
            results.put("equivalentClasses", equivalentClasses);
            
            // Get unsatisfiable classes
            List<Map<String, String>> unsatisfiableList = new ArrayList<>();
            if (!isConsistent) {
                log.warn("Skipping unsatisfiable classes check for inconsistent ontology");
            } else {
                try {
                    Node<OWLClass> bottomNode = reasoner.getUnsatisfiableClasses();
                    Set<OWLClass> unsatisfiable = new HashSet<>(bottomNode.getEntities());
                    unsatisfiable.remove(DATA_FACTORY.getOWLNothing());
                    
                    unsatisfiableList = unsatisfiable.stream()
                        .map(cls -> Map.of(
                            "iri", cls.getIRI().toString(),
                            "label", getLabel(cls, ontology)
                        ))
                        .collect(Collectors.toList());
                } catch (Exception e) {
                    if (type == ReasonerType.ELK) {
                        log.warn("ELK: Error getting unsatisfiable classes - may be due to unsupported OWL constructs", e);
                    } else {
                        log.error("Error getting unsatisfiable classes", e);
                    }
                }
            }
            results.put("unsatisfiableClasses", unsatisfiableList);
            
            // Build property hierarchies
            // NOTE: ELK reasoner doesn't support property hierarchy inference
            List<Map<String, Object>> objectPropertyHierarchy = new ArrayList<>();
            if (type == ReasonerType.ELK) {
                // For ELK, directly provide asserted properties (no inference support)
                log.info("ELK: Providing asserted object properties (no hierarchy inference support)");
                for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
                    if (!prop.isOWLTopObjectProperty() && !prop.isOWLBottomObjectProperty()) {
                        Map<String, Object> node = new HashMap<>();
                        node.put("iri", prop.getIRI().toString());
                        node.put("label", getLabel(prop, ontology));
                        node.put("depth", 0);
                        node.put("childrenCount", 0);
                        objectPropertyHierarchy.add(node);
                    }
                }
            } else {
                try {
                    OWLObjectProperty topObjectProp = DATA_FACTORY.getOWLTopObjectProperty();
                    Set<OWLObjectProperty> processedObjProps = new HashSet<>();
                    buildObjectPropertyHierarchy(reasoner, ontology, topObjectProp, objectPropertyHierarchy, processedObjProps, 0);
                    
                    // Fallback to asserted properties if inferred hierarchy is empty
                    if (objectPropertyHierarchy.isEmpty()) {
                        log.info("Inferred object property hierarchy empty, falling back to asserted properties");
                        for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
                            if (!prop.isOWLTopObjectProperty() && !prop.isOWLBottomObjectProperty()) {
                                Map<String, Object> node = new HashMap<>();
                                node.put("iri", prop.getIRI().toString());
                                node.put("label", getLabel(prop, ontology));
                                node.put("depth", 0);
                                node.put("childrenCount", 0);
                                objectPropertyHierarchy.add(node);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Error building object property hierarchy, falling back to asserted properties", e);
                    // Fallback to asserted properties
                    for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
                        if (!prop.isOWLTopObjectProperty() && !prop.isOWLBottomObjectProperty()) {
                            Map<String, Object> node = new HashMap<>();
                            node.put("iri", prop.getIRI().toString());
                            node.put("label", getLabel(prop, ontology));
                            node.put("depth", 0);
                            node.put("childrenCount", 0);
                            objectPropertyHierarchy.add(node);
                        }
                    }
                }
            }
            results.put("objectPropertyHierarchy", objectPropertyHierarchy);

            List<Map<String, Object>> dataPropertyHierarchy = new ArrayList<>();
            if (type == ReasonerType.ELK) {
                // For ELK, directly provide asserted properties (no inference support)
                log.info("ELK: Providing asserted data properties (no hierarchy inference support)");
                for (OWLDataProperty prop : ontology.getDataPropertiesInSignature()) {
                    if (!prop.isOWLTopDataProperty() && !prop.isOWLBottomDataProperty()) {
                        Map<String, Object> node = new HashMap<>();
                        node.put("iri", prop.getIRI().toString());
                        node.put("label", getLabel(prop, ontology));
                        node.put("depth", 0);
                        node.put("childrenCount", 0);
                        dataPropertyHierarchy.add(node);
                    }
                }
            } else {
                try {
                    OWLDataProperty topDataProp = DATA_FACTORY.getOWLTopDataProperty();
                    Set<OWLDataProperty> processedDataProps = new HashSet<>();
                    buildDataPropertyHierarchy(reasoner, ontology, topDataProp, dataPropertyHierarchy, processedDataProps, 0);
                    
                    // Fallback to asserted properties if inferred hierarchy is empty
                    if (dataPropertyHierarchy.isEmpty()) {
                        log.info("Inferred data property hierarchy empty, falling back to asserted properties");
                        for (OWLDataProperty prop : ontology.getDataPropertiesInSignature()) {
                            if (!prop.isOWLTopDataProperty() && !prop.isOWLBottomDataProperty()) {
                                Map<String, Object> node = new HashMap<>();
                                node.put("iri", prop.getIRI().toString());
                                node.put("label", getLabel(prop, ontology));
                                node.put("depth", 0);
                                node.put("childrenCount", 0);
                                dataPropertyHierarchy.add(node);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Error building data property hierarchy, falling back to asserted properties", e);
                    // Fallback to asserted properties
                    for (OWLDataProperty prop : ontology.getDataPropertiesInSignature()) {
                        if (!prop.isOWLTopDataProperty() && !prop.isOWLBottomDataProperty()) {
                            Map<String, Object> node = new HashMap<>();
                            node.put("iri", prop.getIRI().toString());
                            node.put("label", getLabel(prop, ontology));
                            node.put("depth", 0);
                            node.put("childrenCount", 0);
                            dataPropertyHierarchy.add(node);
                        }
                    }
                }
            }
            results.put("dataPropertyHierarchy", dataPropertyHierarchy);
            
            // Total counts
            int totalClasses = ontology.getClassesInSignature().size();
            results.put("totalClasses", totalClasses);

            Map<String, Object> stats = new HashMap<>();
            stats.put("classHierarchyNodes", totalClasses);
            stats.put("objectPropertyNodes", ontology.getObjectPropertiesInSignature().size());
            stats.put("dataPropertyNodes", ontology.getDataPropertiesInSignature().size());
            stats.put("individuals", ontology.getIndividualsInSignature().size());
            stats.put("satisfiableClasses", isConsistent ? Math.max(0, totalClasses - unsatisfiableList.size()) : -1);
            stats.put("unsatisfiableClasses", isConsistent ? unsatisfiableList.size() : -1);
            stats.put("isConsistent", isConsistent);
            results.put("stats", stats);

            // Add ELK profile warning if ELK reasoner is used
            if (type == ReasonerType.ELK) {
                String elkWarning = "ELK reasoner uses EL profile - complex OWL 2 DL constructs may not be classified correctly. " +
                                    "For full OWL 2 DL support, use HermiT or Pellet reasoner.";
                results.put("reasonerCapabilities", Map.of(
                    "reasoner", "ELK",
                    "profile", "EL (subset of OWL 2 DL)",
                    "warning", elkWarning,
                    "supportsFullOWL2", false
                ));
                log.warn(elkWarning);
            } else {
                results.put("reasonerCapabilities", Map.of(
                    "reasoner", type.getDisplayName(),
                    "profile", "OWL 2 DL",
                    "supportsFullOWL2", true
                ));
            }
            
            log.info("Classification results: {} class nodes, {} object prop nodes, {} data prop nodes",
                classHierarchy.size(), objectPropertyHierarchy.size(), dataPropertyHierarchy.size());
            
            return results;
            
        } catch (Exception e) {
            log.error("Error getting classification results", e);
            return Map.of(
                "classHierarchy", new ArrayList<>(),
                "equivalentClasses", new ArrayList<>(),
                "unsatisfiableClasses", new ArrayList<>(),
                "totalClasses", ontology.getClassesInSignature().size(),
                "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
            );
        }
    }
    
    /**
     * Recursively build class hierarchy
     */
    private void buildClassHierarchy(OWLReasoner reasoner, OWLOntology ontology, OWLClass parentClass,
                                     List<Map<String, Object>> hierarchy, Set<OWLClass> processedClasses, int depth) {
        if (processedClasses.contains(parentClass) || depth > 10) {
            return;
        }
        processedClasses.add(parentClass);


        for (Node<OWLClass> node : reasoner.getSubClasses(parentClass, true)) {
            if (node.isBottomNode()) {
                continue;
            }
            for (OWLClass subClass : node.getEntities()) {
                Map<String, Object> classNode = new HashMap<>();
                classNode.put("iri", subClass.getIRI().toString());
                classNode.put("label", getLabel(subClass, ontology));
                classNode.put("depth", depth);

                // Get children count
                int childrenCount = (int) reasoner.getSubClasses(subClass, true)
                    .getNodes()
                    .stream()
                    .filter(n -> !n.isBottomNode())
                    .flatMap(n -> n.getEntities().stream())
                    .count();
                classNode.put("childrenCount", childrenCount);
                
                hierarchy.add(classNode);
                
                // Recursively process children (limit depth to avoid huge hierarchies)
                if (depth < 5 && childrenCount > 0) {
                    buildClassHierarchy(reasoner, ontology, subClass, hierarchy, processedClasses, depth + 1);
                }
            }
        }
    }

    /**
     * Recursively build object property hierarchy
     */
    private void buildObjectPropertyHierarchy(OWLReasoner reasoner, OWLOntology ontology, OWLObjectProperty parentProp,
                                             List<Map<String, Object>> hierarchy, Set<OWLObjectProperty> processedProps, int depth) {
        if (processedProps.contains(parentProp) || depth > 10) {
            return;
        }
        processedProps.add(parentProp);
        
        for (Node<OWLObjectPropertyExpression> node : reasoner.getSubObjectProperties(parentProp, true)) {
            for (OWLObjectPropertyExpression subPropExpr : node.getEntities()) {
                if (subPropExpr.isAnonymous() || subPropExpr.asOWLObjectProperty().isOWLBottomObjectProperty()) {
                    continue;
                }
                
                OWLObjectProperty subProp = subPropExpr.asOWLObjectProperty();
                Map<String, Object> propNode = new HashMap<>();
                propNode.put("iri", subProp.getIRI().toString());
                propNode.put("label", getLabel(subProp, ontology));
                propNode.put("depth", depth);
                
                int childrenCount = (int) reasoner.getSubObjectProperties(subProp, true)
                    .entities()
                    .filter(p -> !p.isAnonymous() && !p.asOWLObjectProperty().isOWLBottomObjectProperty())
                    .count();
                propNode.put("childrenCount", childrenCount);
                
                hierarchy.add(propNode);
                
                if (depth < 5 && childrenCount > 0) {
                    buildObjectPropertyHierarchy(reasoner, ontology, subProp, hierarchy, processedProps, depth + 1);
                }
            }
        }
    }

    /**
     * Recursively build data property hierarchy
     */
    private void buildDataPropertyHierarchy(OWLReasoner reasoner, OWLOntology ontology, OWLDataProperty parentProp,
                                           List<Map<String, Object>> hierarchy, Set<OWLDataProperty> processedProps, int depth) {
        if (processedProps.contains(parentProp) || depth > 10) {
            return;
        }
        processedProps.add(parentProp);
        
        for (Node<OWLDataProperty> node : reasoner.getSubDataProperties(parentProp, true)) {
            for (OWLDataProperty subProp : node.getEntities()) {
                if (subProp.isOWLBottomDataProperty()) {
                    continue;
                }
                
                Map<String, Object> propNode = new HashMap<>();
                propNode.put("iri", subProp.getIRI().toString());
                propNode.put("label", getLabel(subProp, ontology));
                propNode.put("depth", depth);
                
                int childrenCount = (int) reasoner.getSubDataProperties(subProp, true)
                    .entities()
                    .filter(p -> !p.isOWLBottomDataProperty())
                    .count();
                propNode.put("childrenCount", childrenCount);
                
                hierarchy.add(propNode);
                
                if (depth < 5 && childrenCount > 0) {
                    buildDataPropertyHierarchy(reasoner, ontology, subProp, hierarchy, processedProps, depth + 1);
                }
            }
        }
    }
    
    /**
     * Get label for an OWL entity
     */
    private String getLabel(OWLEntity entity, OWLOntology ontology) {
        return ontology.getAnnotationAssertionAxioms(entity.getIRI()).stream()
            .filter(a -> a.getProperty().isLabel())
            .findFirst()
            .map(a -> a.getValue().asLiteral().map(OWLLiteral::getLiteral).orElse(""))
            .orElse(getLocalName(entity.getIRI().toString()));
    }
    
    /**
     * Get local name from IRI
     */
    private String getLocalName(String iri) {
        int hashIndex = iri.lastIndexOf('#');
        int slashIndex = iri.lastIndexOf('/');
        int splitIndex = Math.max(hashIndex, slashIndex);
        return splitIndex >= 0 && splitIndex < iri.length() - 1
            ? iri.substring(splitIndex + 1)
            : iri;
    }
    
    private static class DatatypeAwareSatisfiabilityChecker extends SatisfiabilityEntailmentChecker {
        private final Set<OWLAxiom> datatypeDefinitions;

        DatatypeAwareSatisfiabilityChecker(OWLReasonerFactory reasonerFactory, OWLAxiom entailment,
                Supplier<OWLOntologyManager> m, Set<OWLAxiom> datatypeDefinitions) {
            super(reasonerFactory, entailment, m);
            this.datatypeDefinitions = datatypeDefinitions;
        }

        @Override
        public boolean isEntailed(Set<OWLAxiom> axioms) {
            Set<OWLAxiom> augmented = new HashSet<>(axioms);
            augmented.addAll(datatypeDefinitions);
            return super.isEntailed(augmented);
        }
    }

    private static class DatatypeAwareSatisfiabilityCheckerFactory implements EntailmentCheckerFactory<OWLAxiom> {
        private final OWLReasonerFactory reasonerFactory;
        private final Supplier<OWLOntologyManager> m;
        private final Set<OWLAxiom> datatypeDefinitions;

        DatatypeAwareSatisfiabilityCheckerFactory(OWLReasonerFactory reasonerFactory, Supplier<OWLOntologyManager> m,
                Set<OWLAxiom> datatypeDefinitions) {
            this.reasonerFactory = reasonerFactory;
            this.m = m;
            this.datatypeDefinitions = datatypeDefinitions;
        }

        @Override
        public EntailmentChecker<OWLAxiom> createEntailementChecker(OWLAxiom entailment) {
            return new DatatypeAwareSatisfiabilityChecker(reasonerFactory, entailment, m, datatypeDefinitions);
        }
    }


    private static final class CancellableExplanationProgressMonitor<E> implements ExplanationProgressMonitor<E> {
        private volatile boolean cancelled = false;

        void cancel() {
            cancelled = true;
        }

        @Override
        public void foundExplanation(ExplanationGenerator<E> generator, Explanation<E> explanation, Set<Explanation<E>> allFoundExplanations) {
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    public List<Map<String, Object>> findEntailmentJustifications(
            OWLOntology ontology, OWLAxiom entailment, ReasonerType type, int limit, String mode) {
        return findEntailmentJustifications(ontology, entailment, type, limit, mode, JUSTIFICATION_TIMEOUT_MS);
    }

    private List<Map<String, Object>> findEntailmentJustifications(
            OWLOntology ontology, OWLAxiom entailment, ReasonerType type, int limit, String mode, long timeoutMs) {
        OWLOntology reasoningOntology = JustificationRendering.stripSwrlRules(ontology);
        OWLReasonerFactory reasonerFactory = getReasonerFactory(type);
        Supplier<OWLOntologyManager> ontologyManagerSupplier = OWLManager::createOWLOntologyManager;

        Set<OWLAxiom> datatypeDefinitions = reasoningOntology.axioms(AxiomType.DATATYPE_DEFINITION)
            .collect(Collectors.toSet());
        EntailmentCheckerFactory<OWLAxiom> checkerFactory = datatypeDefinitions.isEmpty()
            ? new SatisfiabilityEntailmentCheckerFactory(reasonerFactory, ontologyManagerSupplier)
            : new DatatypeAwareSatisfiabilityCheckerFactory(reasonerFactory, ontologyManagerSupplier, datatypeDefinitions);
        ExpansionStrategy<OWLAxiom> expansionStrategy =
            new StructuralTypePriorityExpansionStrategy<>(InitialEntailmentCheckStrategy.PERFORM, ontologyManagerSupplier);
        ContractionStrategy<OWLAxiom> contractionStrategy = new DivideAndConquerContractionStrategy<>();
        Configuration<OWLAxiom> configuration =
            new Configuration<>(checkerFactory, expansionStrategy, contractionStrategy, ontologyManagerSupplier);
        ExplanationGeneratorFactory<OWLAxiom> baseFactory = new BlackBoxExplanationGeneratorFactory<>(configuration);

        ExplanationGeneratorFactory<OWLAxiom> factory = "laconic".equals(mode)
            ? new LaconicExplanationGeneratorFactory<>(baseFactory, ontologyManagerSupplier)
            : baseFactory;

        CancellableExplanationProgressMonitor<OWLAxiom> monitor = new CancellableExplanationProgressMonitor<>();
        ExplanationGenerator<OWLAxiom> generator = factory.createExplanationGenerator(reasoningOntology, monitor);
        Set<Explanation<OWLAxiom>> explanations =
            runWithTimeout(() -> generator.getExplanations(entailment, limit), monitor, timeoutMs);
        return buildJustificationsResult(explanations, ontology);
    }


    public record EquivalentJustificationsResult(List<Map<String, Object>> justifications, List<String> warnings) {}

    public EquivalentJustificationsResult findEquivalentClassesJustifications(
            OWLOntology ontology, String subjectIri, String targetIri, ReasonerType type, int limit, String mode) {
        OWLClass subject = DATA_FACTORY.getOWLClass(IRI.create(subjectIri));
        OWLClass target = DATA_FACTORY.getOWLClass(IRI.create(targetIri));
        String subjectLabel = getLabel(subject, ontology);
        String targetLabel = getLabel(target, ontology);

        int forwardLimit = (limit + 1) / 2;
        int backwardLimit = limit - forwardLimit;
        long deadline = System.currentTimeMillis() + JUSTIFICATION_TIMEOUT_MS;
        List<String> warnings = new ArrayList<>();

        List<Map<String, Object>> forward = safeFindEntailmentJustifications(
            ontology, DATA_FACTORY.getOWLSubClassOfAxiom(subject, target), type, forwardLimit, mode, deadline,
            warnings, subjectLabel + " ⊑ " + targetLabel);
        List<Map<String, Object>> backward = safeFindEntailmentJustifications(
            ontology, DATA_FACTORY.getOWLSubClassOfAxiom(target, subject), type, backwardLimit, mode, deadline,
            warnings, targetLabel + " ⊑ " + subjectLabel);

        List<Map<String, Object>> combined = new ArrayList<>();
        combined.addAll(prefixLabels(forward, subjectLabel + " ⊑ " + targetLabel));
        combined.addAll(prefixLabels(backward, targetLabel + " ⊑ " + subjectLabel));
        return new EquivalentJustificationsResult(combined, warnings);
    }

    private List<Map<String, Object>> safeFindEntailmentJustifications(
            OWLOntology ontology, OWLAxiom entailment, ReasonerType type, int limit, String mode, long deadline,
            List<String> warnings, String directionLabel) {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
            warnings.add(directionLabel + " could not be checked — the shared time budget ran out before this direction started.");
            return Collections.emptyList();
        }
        try {
            return findEntailmentJustifications(ontology, entailment, type, limit, mode, remaining);
        } catch (RuntimeException e) {
            warnings.add(directionLabel + " failed: " + (e.getMessage() != null ? e.getMessage() : "unknown error"));
            return Collections.emptyList();
        }
    }

    private List<Map<String, Object>> prefixLabels(List<Map<String, Object>> justifications, String prefix) {
        for (Map<String, Object> justification : justifications) {
            justification.put("label", prefix + " — " + justification.get("label"));
        }
        return justifications;
    }

    public EquivalentJustificationsResult findEquivalentRestrictionJustifications(
            OWLOntology ontology, String subjectIri, String propertyIri, String fillerIri,
            boolean isDataProperty, String literalValue, ReasonerType type, int limit, String mode) {
        OWLClass subject = DATA_FACTORY.getOWLClass(IRI.create(subjectIri));
        OWLClassExpression restriction = buildEquivalentRestrictionExpression(propertyIri, fillerIri, isDataProperty, literalValue);

        String subjectLabel = getLabel(subject, ontology);
        int forwardLimit = (limit + 1) / 2;
        int backwardLimit = limit - forwardLimit;
        long deadline = System.currentTimeMillis() + JUSTIFICATION_TIMEOUT_MS;
        List<String> warnings = new ArrayList<>();

        List<Map<String, Object>> forward = safeFindEntailmentJustifications(
            ontology, DATA_FACTORY.getOWLSubClassOfAxiom(subject, restriction), type, forwardLimit, mode, deadline,
            warnings, subjectLabel + " ⊑ restriction");
        List<Map<String, Object>> backward = safeFindEntailmentJustifications(
            ontology, DATA_FACTORY.getOWLSubClassOfAxiom(restriction, subject), type, backwardLimit, mode, deadline,
            warnings, "restriction ⊑ " + subjectLabel);

        List<Map<String, Object>> combined = new ArrayList<>();
        combined.addAll(prefixLabels(forward, subjectLabel + " ⊑ restriction"));
        combined.addAll(prefixLabels(backward, "restriction ⊑ " + subjectLabel));
        return new EquivalentJustificationsResult(combined, warnings);
    }

    private OWLClassExpression buildEquivalentRestrictionExpression(
            String propertyIri, String fillerIri, boolean isDataProperty, String literalValue) {
        if (isDataProperty) {
            OWLDataProperty property = DATA_FACTORY.getOWLDataProperty(IRI.create(propertyIri));
            OWLDatatype filler = DATA_FACTORY.getOWLDatatype(IRI.create(fillerIri));
            return "only".equals(literalValue)
                ? DATA_FACTORY.getOWLDataAllValuesFrom(property, filler)
                : DATA_FACTORY.getOWLDataSomeValuesFrom(property, filler);
        }
        OWLObjectProperty property = DATA_FACTORY.getOWLObjectProperty(IRI.create(propertyIri));
        OWLClass filler = DATA_FACTORY.getOWLClass(IRI.create(fillerIri));
        if ("some".equals(literalValue) || "only".equals(literalValue)) {
            return "only".equals(literalValue)
                ? DATA_FACTORY.getOWLObjectAllValuesFrom(property, filler)
                : DATA_FACTORY.getOWLObjectSomeValuesFrom(property, filler);
        }
        return buildObjectCardinalityRestriction(property, filler, literalValue);
    }

    public List<Map<String, Object>> findInconsistencyJustifications(
            OWLOntology ontology, ReasonerType type, int limit, String mode) {
        OWLOntology reasoningOntology = JustificationRendering.stripSwrlRules(ontology);
        OWLReasonerFactory reasonerFactory = getReasonerFactory(type);
        Supplier<OWLOntologyManager> ontologyManagerSupplier = OWLManager::createOWLOntologyManager;
        OWLAxiom inconsistencyEntailment = DATA_FACTORY.getOWLSubClassOfAxiom(
            DATA_FACTORY.getOWLThing(), DATA_FACTORY.getOWLNothing());

        ExplanationGeneratorFactory<OWLAxiom> baseFactory = new InconsistentOntologyExplanationGeneratorFactory(
            reasonerFactory, DATA_FACTORY, ontologyManagerSupplier, JUSTIFICATION_TIMEOUT_MS);
        ExplanationGeneratorFactory<OWLAxiom> factory = "laconic".equals(mode)
            ? new LaconicExplanationGeneratorFactory<>(baseFactory, ontologyManagerSupplier)
            : baseFactory;

        CancellableExplanationProgressMonitor<OWLAxiom> monitor = new CancellableExplanationProgressMonitor<>();
        ExplanationGenerator<OWLAxiom> generator = factory.createExplanationGenerator(reasoningOntology, monitor);
        Set<Explanation<OWLAxiom>> explanations =
            runWithTimeout(() -> generator.getExplanations(inconsistencyEntailment, limit), monitor, JUSTIFICATION_TIMEOUT_MS);
        return buildJustificationsResult(explanations, ontology);
    }

    private Set<Explanation<OWLAxiom>> runWithTimeout(
            Supplier<Set<Explanation<OWLAxiom>>> search,
            CancellableExplanationProgressMonitor<OWLAxiom> monitor,
            long timeoutMs) {
        CompletableFuture<Void> started = new CompletableFuture<>();
        java.util.concurrent.Future<Set<Explanation<OWLAxiom>>> future;
        try {
            future = justificationExecutor.submit(() -> {
                started.complete(null);
                return search.get();
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            throw new RuntimeException("Too many reasoning requests in progress — please try again shortly.");
        }
        try {
            started.get(JUSTIFICATION_QUEUE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new RuntimeException("Too many reasoning requests in progress — please try again shortly.");
        } catch (InterruptedException ie) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Justification search was interrupted", ie);
        } catch (Exception e) {
            future.cancel(true);
            throw new RuntimeException("Justification search failed to start: "
                + (e.getMessage() != null ? e.getMessage() : e.toString()), e);
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            monitor.cancel();
            future.cancel(true);
            throw new RuntimeException(
                "Justification search timed out after " + timeoutMs
                    + "ms — the reasoner may still be warming up on its first request; try again.",
                te);
        } catch (RuntimeException | java.util.concurrent.ExecutionException e) {
            monitor.cancel();
            throw new RuntimeException("Justification search failed: "
                + (e.getMessage() != null ? e.getMessage() : e.toString()), e);
        } catch (InterruptedException ie) {
            monitor.cancel();
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Justification search was interrupted", ie);
        }
    }

    private List<Map<String, Object>> buildJustificationsResult(
            Set<Explanation<OWLAxiom>> explanations, OWLOntology ontology) {
        if (explanations.isEmpty()) {
            return Collections.emptyList();
        }

        List<Set<OWLAxiom>> allAxiomSets = explanations.stream()
            .map(Explanation::getAxioms)
            .collect(Collectors.toList());

        List<Map<String, Object>> justifications = new ArrayList<>();
        int i = 1;
        for (Explanation<OWLAxiom> explanation : explanations) {
            Map<String, Object> justification = new HashMap<>();
            justification.put("label", "Explanation " + i++);

            List<Map<String, Object>> axiomEntries = new ArrayList<>();
            for (OWLAxiom axiom : explanation.getAxioms()) {
                long membershipCount = allAxiomSets.stream()
                    .filter(set -> set.contains(axiom))
                    .count();

                Map<String, Object> entry = new HashMap<>();
                entry.put("text", JustificationRendering.renderAxiom(axiom, ontology, this::getLabel));
                entry.put("membershipNote",
                    membershipCount == allAxiomSets.size() ? "In ALL other justifications"
                    : membershipCount == 1 ? "In NO other justifications"
                    : "In " + (membershipCount - 1) + " other justifications");
                entry.put("entities", JustificationRendering.extractEntities(axiom, ontology, this::getLabel));
                entry.put("role", JustificationRendering.isConflictAxiom(axiom) ? "CONFLICT" : "CONTRIBUTING");
                axiomEntries.add(entry);
            }
            justification.put("axioms", axiomEntries);
            justifications.add(justification);
        }
        return justifications;
    }

    private OWLClassExpression buildObjectCardinalityRestriction(
            OWLObjectProperty property, OWLClass filler, String literalValue) {
        String[] parts = literalValue != null ? literalValue.split(":", 2) : new String[0];
        String kind = parts.length > 0 ? parts[0] : "min";
        int cardinality = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
        switch (kind) {
            case "max":
                return DATA_FACTORY.getOWLObjectMaxCardinality(cardinality, property, filler);
            case "exactly":
                return DATA_FACTORY.getOWLObjectExactCardinality(cardinality, property, filler);
            case "min":
            default:
                return DATA_FACTORY.getOWLObjectMinCardinality(cardinality, property, filler);
        }
    }

    public OWLAxiom buildEntailmentAxiom(String entailmentType, String subjectIri, String targetIri,
            String propertyIri, String literalValue) {
        return self.research.ontology.common.EntailmentAxiomBuilder.buildEntailmentAxiom(
                entailmentType, subjectIri, targetIri, propertyIri, literalValue);
    }

    public Map<String, Object> describeEntailment(OWLAxiom axiom) {
        return JustificationRendering.describeEntailment(axiom);
    }

    /**
     * Realize the ontology (compute instances for all classes)
     */
    public void realize(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        
        try {
            // Check consistency first
            boolean isConsistent = reasoner.isConsistent();
            if (!isConsistent) {
                log.warn("Ontology is inconsistent - skipping realization precomputation");
                return; // Return early without throwing exception
            }
            
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
     * Run reasoner.getTypes() on a worker thread and bound how long we wait for it.
     * On timeout we call reasoner.interrupt() rather than just walking away — same
     * pattern Protege's OWLReasonerManagerImpl#killCurrentClassification uses to stop
     * a running HermiT computation. Once interrupted, Protege never reuses that
     * reasoner (it disposes it in its ReasonerInterruptedException handler), because
     * its internal tableau state can't be trusted after a mid-flight abort — so we
     * evict/dispose it from reasonerCache too, once the abandoned call actually unwinds.
     */
    private NodeSet<OWLClass> getTypesWithTimeout(OWLReasoner reasoner, OWLNamedIndividual individual)
            throws TimeoutException {
        CompletableFuture<NodeSet<OWLClass>> future = CompletableFuture.supplyAsync(
            () -> reasoner.getTypes(individual, true), realizationExecutor);
        try {
            return future.get(PER_INDIVIDUAL_TYPE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            reasoner.interrupt();
            // Don't dispose synchronously here — the worker thread is still inside
            // reasoner.getTypes() and may still be touching its internal state.
            // Evict once that call actually completes/throws instead.
            future.whenComplete((result, ex) -> evictInterruptedReasoner(reasoner));
            throw te;
        } catch (Exception e) {
            throw new RuntimeException("Error getting types for individual " + individual.getIRI(), e);
        }
    }

    /**
     * Discard a reasoner that was interrupted mid-computation so it can't be handed
     * out of reasonerCache again; the next getReasoner() call will build a fresh one.
     */
    private void evictInterruptedReasoner(OWLReasoner reasoner) {
        reasonerCache.entrySet().removeIf(entry -> {
            CompletableFuture<OWLReasoner> future = entry.getValue();
            if (!future.isDone() || future.isCompletedExceptionally() || future.join() != reasoner) {
                return false;
            }
            try {
                reasoner.dispose();
            } catch (Exception e) {
                log.warn("Error disposing interrupted reasoner {}", entry.getKey(), e);
            }
            log.warn("Evicted reasoner {} after interrupt — will be recreated on next use", entry.getKey());
            return true;
        });
    }

    /**
     * Get realization results including instances for all classes
     */
    public Map<String, Object> getRealizationResults(OWLOntology ontology, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        Map<String, Object> results = new HashMap<>();
        
        try {
            // Check consistency first
            boolean isConsistent = true;
            try {
                isConsistent = reasoner.isConsistent();
                results.put("isConsistent", isConsistent);
                if (!isConsistent) {
                    log.warn("Ontology is inconsistent - providing limited realization results");
                }
            } catch (Exception e) {
                log.error("Error checking consistency", e);
                results.put("isConsistent", false);
                isConsistent = false;
            }
            
            List<Map<String, Object>> instances = new ArrayList<>();
            boolean timedOut = false;

            if (!isConsistent) {
                // For inconsistent ontologies, skip reasoner queries
                log.warn("Skipping instance type computation for inconsistent ontology");
            } else {
                // Get all named individuals and their types. Direct types only —
                // ancestor types are already implied by the class hierarchy.
                try {
                    long loopDeadline = System.currentTimeMillis() + REALIZATION_TOTAL_BUDGET_MS;
                    for (OWLNamedIndividual individual : ontology.getIndividualsInSignature()) {
                        if (System.currentTimeMillis() > loopDeadline) {
                            // Even when no single call hangs, each getTypes() call still costs
                            // O(classCount) against HermiT's InstanceManager — with hundreds of
                            // individuals over a huge class hierarchy (e.g. FoodOn's 39k+
                            // classes) that adds up to many minutes in aggregate. Cap the whole
                            // loop's wall-clock time rather than let it run unbounded.
                            log.warn("Realization results computation exceeded {} ms budget with {} of {} "
                                    + "individuals processed — returning partial results",
                                REALIZATION_TOTAL_BUDGET_MS, instances.isEmpty() ? 0 : instances.size(),
                                ontology.getIndividualsInSignature().size());
                            timedOut = true;
                            break;
                        }

                        NodeSet<OWLClass> types;
                        try {
                            types = getTypesWithTimeout(reasoner, individual);
                        } catch (TimeoutException te) {
                            // A single individual can still exceed even the per-call timeout on
                            // a pathological hierarchy; HermiT's work isn't cancellable once
                            // started, so bail out here too rather than wait on it.
                            log.warn("Timed out computing types for individual {} ({} classes in signature) — "
                                    + "returning partial realization results",
                                individual.getIRI(), ontology.getClassesInSignature().size());
                            timedOut = true;
                            break;
                        }

                        for (OWLClass cls : types.getFlattened()) {
                            if (!cls.isOWLThing() && !cls.isOWLNothing()) {
                                Map<String, Object> instance = new HashMap<>();
                                instance.put("individualIri", individual.getIRI().toString());
                                instance.put("individualLabel", getLabel(individual, ontology));
                                instance.put("classIri", cls.getIRI().toString());
                                instance.put("classLabel", getLabel(cls, ontology));
                                instances.add(instance);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Error computing instance types, returning empty", e);
                }
            }

            results.put("instances", instances);
            results.put("totalInstances", instances.size());
            results.put("totalIndividuals", ontology.getIndividualsInSignature().size());
            results.put("timedOut", timedOut);
            if (timedOut) {
                results.put("message", "Instance type computation took too long on this ontology's class "
                        + "hierarchy and was stopped early; results are partial.");
            }

            log.info("Realization results: {} instances computed for {} individuals{}",
                instances.size(), ontology.getIndividualsInSignature().size(),
                timedOut ? " (timed out — partial)" : "");

            return results;
            
        } catch (Exception e) {
            log.error("Error getting realization results", e);
            return Map.of(
                "instances", new ArrayList<>(),
                "totalInstances", 0,
                "totalIndividuals", ontology.getIndividualsInSignature().size(),
                "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
            );
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
    public Set<OWLClass> getInferredSubClasses(OWLOntology ontology, OWLClass owlClass, ReasonerType type) {
        OWLReasoner reasoner = getReasoner(ontology, type);
        
        try {
            NodeSet<OWLClass> subClasses = reasoner.getSubClasses(owlClass, false);
            return subClasses.getFlattened();
        } catch (Exception e) {
            log.error("Error getting inferred subclasses", e);
            return Collections.emptySet();
        }
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

        try {
            NodeSet<OWLClass> types = getTypesWithTimeout(reasoner, individual);
            return types.getFlattened();
        } catch (TimeoutException te) {
            log.warn("Timed out computing inferred types for individual {} ({} classes in signature)",
                individual.getIRI(), ontology.getClassesInSignature().size());
            return Collections.emptySet();
        } catch (Exception e) {
            log.error("Error getting inferred types", e);
            return Collections.emptySet();
        }
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
                        inferredAxioms.add(DATA_FACTORY.getOWLSubClassOfAxiom(owlClass, superClass));
                    }
                }

                // Inferred instance axioms
                Set<OWLNamedIndividual> instances = getInferredInstances(ontology, owlClass, type);
                for (OWLNamedIndividual individual : instances) {
                    inferredAxioms.add(DATA_FACTORY.getOWLClassAssertionAxiom(owlClass, individual));
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
            stats.put("supportsIncrementalReasoning", false);
            stats.put("supportsDatatypeReasoning", type != ReasonerType.STRUCTURAL);
            stats.put("supportsOWL2DL", type == ReasonerType.HERMIT || type == ReasonerType.PELLET);
            
            return stats;
            
        } catch (Exception e) {
            log.error("Error getting reasoner stats", e);
            return stats;
        }
    }
        public Map<String, Object> explainInconsistency(OWLOntology ontology, ReasonerType type) {
        return explainInconsistency(ontology, type, MAX_JUSTIFICATIONS, "regular");
    }

    public Map<String, Object> explainInconsistency(OWLOntology ontology, ReasonerType type, int maxJustifications) {
        return explainInconsistency(ontology, type, maxJustifications, "regular");
    }

    /**
     * Explain why the ontology is inconsistent
     * Returns detailed information about contradictions and problematic axioms
     */
    public Map<String, Object> explainInconsistency(OWLOntology ontology, ReasonerType type, int maxJustifications, String mode) {
        OWLOntology reasoningOntology = JustificationRendering.stripSwrlRules(ontology);
        OWLReasoner reasoner = createReasoner(reasoningOntology, type);
        Map<String, Object> explanation = new HashMap<>();
        
        try {
            boolean isConsistent = reasoner.isConsistent();
            explanation.put("isConsistent", isConsistent);
            
            if (isConsistent) {
                explanation.put("message", "Ontology is consistent - no explanation needed");
                explanation.put("causes", new ArrayList<>());
                return explanation;
            }
            
            log.info("Analyzing inconsistency causes");
            List<Map<String, Object>> causes = new ArrayList<>();

            // 1. Global-inconsistency note. reasoner.getUnsatisfiableClasses() throws
            // InconsistentOntologyException whenever the ontology is inconsistent — the
            // only state this method ever runs in (see the early return above) — so it
            // can never enumerate a meaningful subset here. That's expected under OWL
            // semantics anyway: with no models, every class is vacuously equivalent to
            // owl:Nothing. Surface that as context instead of a broken "unsatisfiable
            // classes" list; the concrete causes below are what's actually actionable.
            Map<String, Object> globalNote = new HashMap<>();
            globalNote.put("type", "GLOBAL_INCONSISTENCY");
            globalNote.put("severity", "INFO");
            globalNote.put("title", "Every Class Is Vacuously Unsatisfiable");
            globalNote.put("description", "The ontology as a whole has no valid models, so every class is "
                + "technically equivalent to owl:Nothing. The specific causes below identify which asserted "
                + "axioms are actually responsible.");
            causes.add(globalNote);

            // 2. Check for disjoint class violations
            try {
                List<Map<String, Object>> disjointViolations = findDisjointClassViolations(ontology);
                if (!disjointViolations.isEmpty()) {
                    Map<String, Object> cause = new HashMap<>();
                    cause.put("type", "DISJOINT_VIOLATIONS");
                    cause.put("severity", "ERROR");
                    cause.put("title", "Disjoint Class Violations");
                    cause.put("description", "Found individuals or class assertions that violate disjointness constraints");
                    cause.put("violations", disjointViolations);
                    causes.add(cause);
                }
            } catch (Exception e) {
                log.error("Error checking disjoint violations", e);
            }
            
            // 3. Check for property domain/range violations
            try {
                List<Map<String, Object>> propertyViolations = findPropertyViolations(ontology);
                if (!propertyViolations.isEmpty()) {
                    Map<String, Object> cause = new HashMap<>();
                    cause.put("type", "PROPERTY_VIOLATIONS");
                    cause.put("severity", "ERROR");
                    cause.put("title", "Property Domain/Range Conflicts");
                    cause.put("description", "A property assertion entails a type for one of its endpoints "
                        + "(via ObjectPropertyDomain/Range) that is declared disjoint with a type the individual "
                        + "already has asserted");
                    cause.put("violations", propertyViolations);
                    causes.add(cause);
                }
            } catch (Exception e) {
                log.error("Error checking property violations", e);
            }

            try {
                List<Map<String, Object>> justifications =
                    findInconsistencyJustifications(ontology, type, maxJustifications, mode);
                if (!justifications.isEmpty()) {
                    Map<String, Object> cause = new HashMap<>();
                    cause.put("type", "JUSTIFICATIONS");
                    cause.put("severity", "ERROR");
                    cause.put("title", "laconic".equals(mode)
                        ? "Laconic Inconsistency Justifications"
                        : "Minimal Inconsistency Justifications");
                    cause.put("description", "laconic".equals(mode)
                        ? "Each axiom below has been trimmed to just the part actually responsible for the "
                            + "contradiction, with unrelated conjuncts or restrictions removed."
                        : "Minimal sets of asserted axioms that each independently make "
                            + "the ontology inconsistent. Each explanation below is a self-contained, provably "
                            + "sufficient cause — removing any single axiom from it would resolve that path.");
                    cause.put("justifications", justifications);
                    causes.add(cause);
                }

            } catch (Exception e) {
                log.error("Error running justification search", e);
            }

            // 4. General recommendations
            Map<String, Object> recommendations = new HashMap<>();
            recommendations.put("type", "RECOMMENDATIONS");
            recommendations.put("title", "How to Fix");
            List<String> tips = new ArrayList<>();
            tips.add("Review the disjoint/property violations listed above");
            tips.add("Check for conflicting disjointness declarations");
            tips.add("Examine cardinality restrictions (min/max constraints)");
            tips.add("Verify property domain and range definitions");
            tips.add("Look for circular or contradictory class definitions");
            recommendations.put("tips", tips);
            causes.add(recommendations);
            
            explanation.put("causes", causes);
            explanation.put("totalIssues", causes.stream()
                .filter(c -> !"RECOMMENDATIONS".equals(c.get("type")) && !"GLOBAL_INCONSISTENCY".equals(c.get("type")))
                .count());
            
            return explanation;
            
        } catch (Exception e) {
            log.error("Error explaining inconsistency", e);
            explanation.put("error", e.getMessage());
            return explanation;
        }
    }
    
    /**
     * Find disjoint class violations.
     *
     * Types are computed syntactically — asserted ClassAssertion axioms closed over
     * asserted SubClassOf/EquivalentClasses edges — rather than via reasoner.getTypes().
     * The reasoner throws InconsistentOntologyException for any post-classification
     * query once the ontology is inconsistent, which is the only state this method is
     * ever called in (verified against HermiT: getTypes() and getUnsatisfiableClasses()
     * both refuse to run and the violations were silently dropped as a result).
     */
    private List<Map<String, Object>> findDisjointClassViolations(OWLOntology ontology) {
        List<Map<String, Object>> violations = new ArrayList<>();

        List<OWLDisjointClassesAxiom> disjointAxioms =
            new ArrayList<>(ontology.getAxioms(AxiomType.DISJOINT_CLASSES));
        if (disjointAxioms.isEmpty()) {
            return violations;
        }

        Map<OWLNamedIndividual, TypeProvenance> provenanceByIndividual = new HashMap<>();
        for (OWLNamedIndividual individual : ontology.getIndividualsInSignature()) {
            provenanceByIndividual.put(individual, getAssertedTypesClosureWithProvenance(ontology, individual));
        }

        for (OWLDisjointClassesAxiom axiom : disjointAxioms) {
            List<OWLClass> disjointClasses = axiom.getClassesInSignature().stream()
                .filter(c -> !c.isAnonymous())
                .collect(Collectors.toList());

            if (disjointClasses.size() < 2) {
                continue;
            }

            for (Map.Entry<OWLNamedIndividual, TypeProvenance> entry : provenanceByIndividual.entrySet()) {
                TypeProvenance provenance = entry.getValue();
                List<OWLClass> violatingClasses = disjointClasses.stream()
                    .filter(provenance.cameFrom::containsKey)
                    .collect(Collectors.toList());

                if (violatingClasses.size() > 1) {
                    Map<String, Object> violation = new HashMap<>();
                    violation.put("individual", getLabel(entry.getKey(), ontology));
                    violation.put("individualIri", entry.getKey().getIRI().toString());
                    List<String> classLabels = violatingClasses.stream()
                        .map(c -> getLabel(c, ontology))
                        .collect(Collectors.toList());
                    violation.put("disjointClasses", classLabels);
                    List<Map<String, Object>> typeDerivations = violatingClasses.stream()
                        .map(c -> {
                            Map<String, Object> derivation = new HashMap<>();
                            derivation.put("class", getLabel(c, ontology));
                            derivation.put("via", buildDerivationChain(c, provenance, ontology));
                            return derivation;
                        })
                        .collect(Collectors.toList());
                    violation.put("suggestedFix", buildDisjointFixSuggestion(getLabel(entry.getKey(), ontology), typeDerivations));
                    // Per-class derivation: null "via" means the type was asserted
                    // directly; otherwise the chain explains the inheritance path
                    // (e.g. "VegetarianPizza ⊑ Pizza") the way Protégé's own
                    // alternate justifications do — without enumerating every
                    // redundant proof the way Protégé's "8 explanations" view does.
                    violation.put("typeDerivations", violatingClasses.stream()
                        .map(c -> {
                            Map<String, Object> derivation = new HashMap<>();
                            derivation.put("class", getLabel(c, ontology));
                            derivation.put("via", buildDerivationChain(c, provenance, ontology));
                            return derivation;
                        })
                        .collect(Collectors.toList()));
                    violations.add(violation);

                    if (violations.size() >= 5) {
                        return violations; // Limit results
                    }
                }
            }
        }

        return violations;
    }
    private String buildDisjointFixSuggestion(String individualLabel, List<Map<String, Object>> typeDerivations) {
        List<String> direct = new ArrayList<>();
        List<String> inherited = new ArrayList<>();
        for (Map<String, Object> d : typeDerivations) {
            String className = (String) d.get("class");
            String via = (String) d.get("via");
            if (via == null) {
                direct.add(className);
            } else {
                inherited.add(className + " (via " + via + ")");
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("These classes are declared mutually exclusive, so ")
          .append(individualLabel).append(" cannot belong to more than one.");

        if (!direct.isEmpty()) {
            sb.append(" ").append(direct.size() == 1 ? "One option: remove " : "One option: remove all but one of ")
              .append(individualLabel).append("'s direct membership in ")
              .append(String.join(" and ", direct)).append(".");
        }
        if (!inherited.isEmpty()) {
            sb.append(" Note that ").append(String.join(" and ", inherited))
              .append(" wasn't asserted directly — it came from an inheritance chain, "
                  + "so the real fix might be higher up: reconsider that inherited rule instead of editing "
                  + individualLabel).append(" directly.");
        }
        return sb.toString();
    }
    /**
     * Asserted types for an individual, closed over asserted SubClassOf and
     * EquivalentClasses edges between named classes. Purely syntactic (no reasoner
     * call), so it stays usable even when the ontology is inconsistent. Anonymous
     * class expressions (restrictions, etc.) are not expanded — this mirrors what a
     * laconic justification would show (Protégé's own explanation for a simple
     * disjoint-violation case walks the same asserted type + SubClassOf chain).
     */
    private Set<OWLClass> getAssertedTypesClosure(OWLOntology ontology, OWLNamedIndividual individual) {
        return getAssertedTypesClosureWithProvenance(ontology, individual).cameFrom.keySet();
    }

    /**
     * Bundles the BFS parent-pointer map from {@link #getAssertedTypesClosureWithProvenance}
     * with any disjoint-union case-split explanations, which don't fit the plain
     * "X ⊑ Y" chain format (see that method's javadoc for why they need their own slot).
     */
    private static final class TypeProvenance {
        final Map<OWLClass, OWLClass> cameFrom;
        final Map<OWLClass, String> unionDerivations;
        TypeProvenance(Map<OWLClass, OWLClass> cameFrom, Map<OWLClass, String> unionDerivations) {
            this.cameFrom = cameFrom;
            this.unionDerivations = unionDerivations;
        }
    }

    /**
     * Same closure as {@link #getAssertedTypesClosure}, but also returns a BFS
     * parent-pointer map so callers can explain *how* a type was reached — e.g.
     * Pizza2 is typed VegetarianPizza directly, and Pizza only via VegetarianPizza
     * SubClassOf Pizza. A type mapped to {@code null} was asserted directly on the
     * individual; a type mapped to another class was reached through that class.
     * First-writer-wins (BFS visits direct assertions before their superclasses),
     * so a type that is both directly asserted and reachable by inheritance is
     * reported as direct — the simpler explanation, matching Protégé's own
     * preference for its least-involved justification.
     *
     * Also follows owl:disjointUnionOf: DisjointUnion(A, B1, ..., Bn) means every
     * instance of A is an instance of exactly one Bi. That's a case-split, not a
     * simple edge — but if every Bi shares a common (named) ancestor C, then an
     * instance of A is C in every case, so C is a sound entailment regardless of
     * which branch holds. This is exactly the reasoning HermiT performs and Protégé
     * shows as a multi-step justification (e.g. VegetarianPizza's disjoint union of
     * MeatTopping/SeafoodTopping, both ⊑ PizzaTopping) — our syntactic closure
     * otherwise has no way to reach PizzaTopping from a VegetarianPizza assertion.
     */
    private TypeProvenance getAssertedTypesClosureWithProvenance(OWLOntology ontology, OWLNamedIndividual individual) {
        Map<OWLClass, OWLClass> cameFrom = new LinkedHashMap<>();
        Map<OWLClass, String> unionDerivations = new LinkedHashMap<>();
        Deque<OWLClass> frontier = new ArrayDeque<>();

        for (OWLClassAssertionAxiom ax : ontology.getClassAssertionAxioms(individual)) {
            OWLClassExpression ce = ax.getClassExpression();
            if (!ce.isAnonymous()) {
                OWLClass cls = ce.asOWLClass();
                if (!cameFrom.containsKey(cls)) {
                    cameFrom.put(cls, null);
                    frontier.push(cls);
                }
            }
        }

        while (!frontier.isEmpty()) {
            OWLClass current = frontier.pop();

            for (OWLSubClassOfAxiom ax : ontology.getSubClassAxiomsForSubClass(current)) {
                OWLClassExpression sup = ax.getSuperClass();
                if (!sup.isAnonymous()) {
                    OWLClass supCls = sup.asOWLClass();
                    if (!cameFrom.containsKey(supCls)) {
                        cameFrom.put(supCls, current);
                        frontier.push(supCls);
                    }
                }
            }

            for (OWLEquivalentClassesAxiom ax : ontology.getEquivalentClassesAxioms(current)) {
                for (OWLClassExpression member : ax.getClassExpressions()) {
                    if (!member.isAnonymous()) {
                        OWLClass memberCls = member.asOWLClass();
                        if (!cameFrom.containsKey(memberCls)) {
                            cameFrom.put(memberCls, current);
                            frontier.push(memberCls);
                        }
                    }
                }
            }

            for (OWLDisjointUnionAxiom duAxiom : ontology.getDisjointUnionAxioms(current)) {
                List<OWLClass> disjuncts = duAxiom.getClassExpressions().stream()
                    .filter(ce -> !ce.isAnonymous())
                    .map(OWLClassExpression::asOWLClass)
                    .collect(Collectors.toList());
                if (disjuncts.isEmpty()) {
                    continue;
                }

                Set<OWLClass> commonAncestors = null;
                for (OWLClass disjunct : disjuncts) {
                    Set<OWLClass> supers = computeAllSuperclasses(ontology, disjunct);
                    commonAncestors = (commonAncestors == null) ? new HashSet<>(supers)
                        : intersect(commonAncestors, supers);
                }
                if (commonAncestors == null) {
                    continue;
                }

                String disjunctLabels = disjuncts.stream()
                    .map(d -> getLabel(d, ontology))
                    .collect(Collectors.joining(" or "));
                for (OWLClass ancestor : commonAncestors) {
                    if (!cameFrom.containsKey(ancestor)) {
                        cameFrom.put(ancestor, current);
                        unionDerivations.put(ancestor, getLabel(current, ontology) + " is a disjoint union of "
                            + disjunctLabels + " — both are " + getLabel(ancestor, ontology));
                        frontier.push(ancestor);
                    }
                }
            }
        }

        return new TypeProvenance(cameFrom, unionDerivations);
    }

    /** All named classes reachable upward from {@code start} via SubClassOf/EquivalentClasses, including itself. */
    private Set<OWLClass> computeAllSuperclasses(OWLOntology ontology, OWLClass start) {
        Set<OWLClass> result = new HashSet<>();
        Deque<OWLClass> frontier = new ArrayDeque<>();
        result.add(start);
        frontier.push(start);
        while (!frontier.isEmpty()) {
            OWLClass current = frontier.pop();
            for (OWLSubClassOfAxiom ax : ontology.getSubClassAxiomsForSubClass(current)) {
                OWLClassExpression sup = ax.getSuperClass();
                if (!sup.isAnonymous() && result.add(sup.asOWLClass())) {
                    frontier.push(sup.asOWLClass());
                }
            }
            for (OWLEquivalentClassesAxiom ax : ontology.getEquivalentClassesAxioms(current)) {
                for (OWLClassExpression member : ax.getClassExpressions()) {
                    if (!member.isAnonymous() && result.add(member.asOWLClass())) {
                        frontier.push(member.asOWLClass());
                    }
                }
            }
        }
        return result;
    }

    private Set<OWLClass> intersect(Set<OWLClass> a, Set<OWLClass> b) {
        Set<OWLClass> result = new HashSet<>(a);
        result.retainAll(b);
        return result;
    }

    /**
     * Human-readable derivation for a type reached via inheritance, e.g.
     * "VegetarianPizza ⊑ Pizza", or a disjoint-union explanation, e.g. "VegetarianPizza
     * is a disjoint union of MeatTopping or SeafoodTopping — both are PizzaTopping".
     * Returns null when the type was asserted directly on the individual — there's no
     * derivation to explain, and the UI should just show the class with no extra note.
     */
    private String buildDerivationChain(OWLClass cls, TypeProvenance provenance, OWLOntology ontology) {
        if (provenance.unionDerivations.containsKey(cls)) {
            return provenance.unionDerivations.get(cls);
        }
        if (provenance.cameFrom.get(cls) == null) {
            return null;
        }
        List<String> chain = new ArrayList<>();
        OWLClass current = cls;
        while (current != null) {
            chain.add(0, getLabel(current, ontology));
            current = provenance.cameFrom.get(current);
        }
        return String.join(" ⊑ ", chain);
    }
    
    /**
     * Find property domain/range violations.
     *
     * A domain/range axiom only actually contradicts something when the entailed type
     * it implies is disjoint with a type the endpoint individual already has asserted —
     * merely having a domain/range axiom defined proves nothing on its own (that was the
     * previous, always-true heuristic here: it flagged every constrained property whether
     * or not any assertion of it existed). Purely syntactic — reuses the same asserted-type
     * closure as findDisjointClassViolations — so, like that method, it stays usable when
     * the ontology is inconsistent.
     */
    private List<Map<String, Object>> findPropertyViolations(OWLOntology ontology) {
        List<Map<String, Object>> violations = new ArrayList<>();

        for (OWLObjectProperty prop : ontology.getObjectPropertiesInSignature()) {
            Set<OWLClass> domains = ontology.getObjectPropertyDomainAxioms(prop).stream()
                .map(OWLObjectPropertyDomainAxiom::getDomain)
                .filter(d -> !d.isAnonymous())
                .map(OWLClassExpression::asOWLClass)
                .collect(Collectors.toSet());

            Set<OWLClass> ranges = ontology.getObjectPropertyRangeAxioms(prop).stream()
                .map(OWLObjectPropertyRangeAxiom::getRange)
                .filter(r -> !r.isAnonymous())
                .map(OWLClassExpression::asOWLClass)
                .collect(Collectors.toSet());

            if (domains.isEmpty() && ranges.isEmpty()) {
                continue;
            }

            for (OWLObjectPropertyAssertionAxiom assertion : ontology.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION)) {
                if (assertion.getProperty().isAnonymous() || !assertion.getProperty().asOWLObjectProperty().equals(prop)) {
                    continue;
                }
                if (assertion.getSubject().isAnonymous() || assertion.getObject().isAnonymous()) {
                    continue; // Anonymous individuals aren't reachable via getAssertedTypesClosure.
                }
                OWLNamedIndividual subject = assertion.getSubject().asOWLNamedIndividual();
                OWLNamedIndividual object = assertion.getObject().asOWLNamedIndividual();

                if (!domains.isEmpty()) {
                    Set<OWLClass> subjectTypes = getAssertedTypesClosure(ontology, subject);
                    for (OWLClass domain : domains) {
                        OWLClass conflict = findDisjointConflict(ontology, subjectTypes, domain);
                        if (conflict != null) {
                            violations.add(buildPropertyViolation(
                                prop, "domain", subject, domain, conflict, ontology));
                            if (violations.size() >= 5) {
                                return violations;
                            }
                        }
                    }
                }

                if (!ranges.isEmpty()) {
                    Set<OWLClass> objectTypes = getAssertedTypesClosure(ontology, object);
                    for (OWLClass range : ranges) {
                        OWLClass conflict = findDisjointConflict(ontology, objectTypes, range);
                        if (conflict != null) {
                            violations.add(buildPropertyViolation(
                                prop, "range", object, range, conflict, ontology));
                            if (violations.size() >= 5) {
                                return violations;
                            }
                        }
                    }
                }
            }
        }

        return violations;
    }

    /**
     * Returns the asserted type in {@code assertedTypes} that is declared disjoint with
     * {@code required}, or null if {@code required} is already satisfied (present or no
     * conflict found). Sound but conservative: only a direct DisjointClasses co-membership
     * counts, so this never reports a violation the assertions don't actually force.
     */
    private OWLClass findDisjointConflict(OWLOntology ontology, Set<OWLClass> assertedTypes, OWLClass required) {
        if (assertedTypes.contains(required)) {
            return null;
        }
        for (OWLDisjointClassesAxiom axiom : ontology.getDisjointClassesAxioms(required)) {
            for (OWLClass other : axiom.getClassesInSignature()) {
                if (!other.equals(required) && assertedTypes.contains(other)) {
                    return other;
                }
            }
        }
        return null;
    }

    private Map<String, Object> buildPropertyViolation(OWLObjectProperty prop, String constraintKind,
            OWLNamedIndividual individual, OWLClass required, OWLClass conflict, OWLOntology ontology) {
        Map<String, Object> violation = new HashMap<>();
        String individualLabel = getLabel(individual, ontology);
        String propLabel = getLabel(prop, ontology);
        String requiredLabel = getLabel(required, ontology);
        String conflictLabel = getLabel(conflict, ontology);
        violation.put("property", propLabel);
        violation.put("propertyIri", prop.getIRI().toString());
        violation.put("constraintKind", constraintKind);
        violation.put("individual", individualLabel);
        violation.put("individualIri", individual.getIRI().toString());
        violation.put("requiredClass", requiredLabel);
        violation.put("conflictingClass", conflictLabel);
        violation.put("suggestedFix", "The " + constraintKind + " of " + propLabel + " requires " + individualLabel
            + " to be a " + requiredLabel + ", but it's already asserted as " + conflictLabel
            + ", which is disjoint with " + requiredLabel + ". Either remove " + individualLabel + "'s "
            + conflictLabel + " type, stop using it with " + propLabel + ", or reconsider whether "
            + requiredLabel + " and " + conflictLabel + " should really be disjoint.");
        return violation;
    }
    
    /**
     * Clear reasoner cache
     */
    public void clearCache() {
        log.info("Clearing reasoner cache ({} entries)", reasonerCache.size());

        Iterator<Map.Entry<String, CompletableFuture<OWLReasoner>>> it = reasonerCache.entrySet().iterator();
        while (it.hasNext()) {
            CompletableFuture<OWLReasoner> future = it.next().getValue();
            it.remove();
            disposeWhenBuilt(future);
        }
    }

    /**
     * Dispose every cached reasoner wrapping the given ontology instance.
     * Called when the controller's ontology LRU evicts an entry, so a later
     * reload cannot be served a reasoner built on the evicted ontology.
     */
    public void disposeReasoners(OWLOntology ontology) {
        Long identity;
        synchronized (ontologyIdentitySequence) {
            identity = ontologyIdentitySequence.remove(ontology);
        }
        if (identity == null) {
            return;
        }
        String keyPrefix = identity + "-" + ontology.getOntologyID() + "-";
        Iterator<Map.Entry<String, CompletableFuture<OWLReasoner>>> it = reasonerCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CompletableFuture<OWLReasoner>> entry = it.next();
            if (!entry.getKey().startsWith(keyPrefix)) {
                continue;
            }
            it.remove();
            disposeWhenBuilt(entry.getValue());
            log.info("Disposed reasoner {} for evicted ontology", entry.getKey());
        }
    }

    /**
     * Dispose reasoner for specific ontology
     */
    public void disposeReasoner(String ontologyId, ReasonerType type) {
        String cacheKey = ontologyId + "-" + type.name();
        CompletableFuture<OWLReasoner> future = reasonerCache.remove(cacheKey);

        if (future != null) {
            disposeWhenBuilt(future);
            log.info("Disposed {} reasoner for ontology {}", type.getDisplayName(), ontologyId);
        }
    }
}
