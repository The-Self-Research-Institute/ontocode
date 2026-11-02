package self.research.ontology.owlEditor.service;

import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.reasoner.*;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import uk.ac.manchester.cs.jfact.JFactFactory;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import self.research.ontology.common.SwrlBuiltIns;
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
 * Supports multiple reasoners: HermiT, FaCT++, ELK, and Structural.
 */
@Service("owlEditorReasonerService")
public class ReasonerService {

    private static final Logger log = LoggerFactory.getLogger(ReasonerService.class);

    private static final int INFERRED_AXIOM_COUNT_MAX_CLASSES = 2000;

    private enum Warmup { ALL, CLASS_HIERARCHY, NONE }

    private static final int MAX_CACHED_REASONERS = 5;

    private final Map<OWLReasoner, Long> reasonerBytes = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    private volatile self.research.ontology.common.admission.CacheAccount reasonerAccount =
            RetainedMemory.detachedAccount("reasoners");

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCapacityLedger(self.research.ontology.common.admission.ReservationLedger ledger) {
        this.reasonerAccount = ledger.cacheAccount("reasoners");
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private HeavyWorkAdmission heavyWork = HeavyWorkAdmission.disabled();

    @Value("${ontocode.reasoner.lease-wait-ms:15000}")
    private long leaseWaitMs = 15_000L;

    void useAdmission(HeavyWorkAdmission admission) {
        this.heavyWork = admission;
    }

    private OWLReasoner accounted(OWLReasoner reasoner, OWLOntology ontology) {
        long bytes = RetainedMemory.reasonerBytes(ontology.getAxiomCount());
        if (bytes > 0 && reasonerBytes.putIfAbsent(reasoner, bytes) == null) {
            reasonerAccount.add(bytes);
        }
        return reasoner;
    }

    static final class Key {
        private final java.lang.ref.WeakReference<OWLOntology> ontology;
        private final int hash;
        private final ReasonerType type;

        Key(OWLOntology ontology, ReasonerType type) {
            this.ontology = new java.lang.ref.WeakReference<>(ontology);
            this.hash = System.identityHashCode(ontology) * 31 + type.ordinal();
            this.type = type;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that) || that.type != type || that.hash != hash) {
                return false;
            }
            OWLOntology mine = ontology.get();
            return mine != null && mine == that.ontology.get();
        }

        @Override
        public int hashCode() {
            return hash;
        }

        OWLOntology ontologyOrNull() {
            return ontology.get();
        }

        @Override
        public String toString() {
            return "ontology@" + Integer.toHexString(hash) + "-" + type.name();
        }
    }

    final class Entry {
        final Key key;
        final CompletableFuture<OWLReasoner> future = new CompletableFuture<>();
        final java.util.concurrent.locks.ReentrantLock useLock = new java.util.concurrent.locks.ReentrantLock();
        final java.util.concurrent.atomic.AtomicBoolean partialWarmup = new java.util.concurrent.atomic.AtomicBoolean();
        private int borrowers;
        private boolean retired;
        private boolean disposed;

        Entry(Key key) {
            this.key = key;
        }

        boolean tryBorrow() {
            synchronized (this) {
                if (retired) {
                    return false;
                }
                borrowers++;
                return true;
            }
        }

        void borrowAgain() {
            synchronized (this) {
                borrowers++;
            }
        }

        void release() {
            boolean dispose;
            synchronized (this) {
                borrowers--;
                dispose = retired && borrowers == 0 && !disposed;
                if (dispose) {
                    disposed = true;
                }
            }
            if (dispose) {
                disposeWhenBuilt();
            }
        }

        void retire() {
            boolean dispose;
            synchronized (this) {
                retired = true;
                dispose = borrowers == 0 && !disposed;
                if (dispose) {
                    disposed = true;
                }
            }
            if (dispose) {
                disposeWhenBuilt();
            }
        }

        synchronized int borrowerCount() {
            return borrowers;
        }

        synchronized boolean isDisposed() {
            return disposed;
        }

        private void disposeWhenBuilt() {
            future.whenComplete((reasoner, error) -> {
                if (reasoner == null) {
                    return;
                }
                log.info("[ReasonerCache] Disposing reasoner: {}", key);
                try {
                    reasoner.dispose();
                } catch (Exception ignored) {
                }
                Long bytes = reasonerBytes.remove(reasoner);
                if (bytes != null) {
                    reasonerAccount.remove(bytes);
                }
            });
        }
    }

    private final Cache<Key, Entry> reasonerCache = Caffeine.newBuilder()
        .maximumSize(MAX_CACHED_REASONERS)
        .expireAfterAccess(15, TimeUnit.MINUTES)
        .executor(Runnable::run)
        .removalListener((Key key, Entry entry, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
            if (entry != null) {
                log.info("[ReasonerCache] Evicting reasoner: {} (cause={})", key, cause);
                entry.retire();
            }
        })
        .build();

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

    public ReasonerLease lease(OWLOntology ontology, ReasonerType type) {
        return acquire(ontology, type, Warmup.ALL, false);
    }

    public ReasonerLease leaseClassHierarchy(OWLOntology ontology, ReasonerType type, boolean oneShot) {
        return acquire(ontology, type, Warmup.CLASS_HIERARCHY, oneShot);
    }

    public ReasonerLease leaseUnwarmed(OWLOntology ontology, ReasonerType type, boolean oneShot) {
        return acquire(ontology, type, Warmup.NONE, oneShot);
    }

    public Optional<ReasonerLease> borrowCachedReasoner(OWLOntology ontology) {
        for (ReasonerType type : ReasonerType.values()) {
            Entry entry = reasonerCache.getIfPresent(new Key(ontology, type));
            if (entry == null || !entry.future.isDone() || entry.future.isCompletedExceptionally()
                    || !entry.tryBorrow()) {
                continue;
            }
            boolean locked = false;
            try {
                locked = entry.useLock.tryLock(0, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!locked) {
                entry.release();
                continue;
            }
            return Optional.of(new ReasonerLease(this, entry, entry.future.join(), true, false));
        }
        return Optional.empty();
    }

    private String ownerFor(OWLOntology ontology) {
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() != null) {
            return HeavyWorkAdmission.ownerKey(null);
        }
        String iri = ontology.getOntologyID().getOntologyIRI().map(Object::toString).orElse(null);
        return "reasoner:" + (iri != null ? iri : "ontology-" + System.identityHashCode(ontology));
    }

    private ReasonerLease acquire(OWLOntology ontology, ReasonerType type, Warmup warmup, boolean oneShot) {
        Key key = new Key(ontology, type);
        Entry entry = null;
        for (int attempt = 0; attempt < 3 && entry == null; attempt++) {
            Entry candidate = oneShot ? null : reasonerCache.getIfPresent(key);
            if (candidate == null) {
                candidate = oneShot ? startEntry(null, ontology, type, warmup, admitBuild(ontology))
                        : cachedEntry(key, ontology, type, warmup);
            }
            if (candidate.tryBorrow()) {
                entry = candidate;
            }
        }
        if (entry == null) {
            throw ReasoningTaskRunner.busy();
        }

        OWLReasoner reasoner;
        try {
            reasoner = entry.future.get(reasonerOperationTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            discard(entry, oneShot);
            throw new RuntimeException(type.getDisplayName() + " reasoning timed out after "
                    + (reasonerOperationTimeoutMs / 1000) + "s. The ontology may be too large or complex "
                    + "for this reasoner. Try a different reasoner or a smaller scope.");
        } catch (InterruptedException e) {
            entry.release();
            if (oneShot) {
                entry.retire();
            }
            Thread.currentThread().interrupt();
            throw new RuntimeException(type.getDisplayName() + " reasoning was interrupted", e);
        } catch (Exception e) {
            discard(entry, oneShot);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause instanceof RuntimeException re ? re : new RuntimeException(cause);
        }

        boolean locked = false;
        try {
            locked = entry.useLock.tryLock(leaseWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!locked) {
            entry.release();
            if (oneShot) {
                entry.retire();
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new RuntimeException(type.getDisplayName() + " reasoning was interrupted");
            }
            throw new BusyException(self.research.ontology.common.admission.RefusalReason.OWNER_LIMIT,
                    "Another reasoning request on this ontology is still running. Please try again in a moment.",
                    ReasoningTaskRunner.RETRY_AFTER_MILLIS);
        }
        ReasonerLease lease = new ReasonerLease(this, entry, reasoner, true, oneShot);
        try {
            if (warmup == Warmup.ALL && entry.partialWarmup.compareAndSet(true, false)) {
                precomputeAll(reasoner, type);
            }
            try {
                reasoner.flush();
            } catch (Exception e) {
                log.warn("Failed to flush reasoner: {}", e.getMessage());
            }
        } catch (RuntimeException | Error e) {
            lease.close();
            throw e;
        }
        return lease;
    }

    private void discard(Entry entry, boolean oneShot) {
        entry.release();
        if (!oneShot && entry.key != null) {
            reasonerCache.asMap().remove(entry.key, entry);
        }
        entry.retire();
    }

    private Entry cachedEntry(Key key, OWLOntology ontology, ReasonerType type, Warmup warmup) {
        return reasonerCache.get(key, k -> startEntry(k, ontology, type, warmup, admitBuild(ontology)));
    }

    private HeavyWorkAdmission.Hold admitBuild(OWLOntology ontology) {
        return heavyWork.admitLoad(ownerFor(ontology), self.research.ontology.common.admission.WorkKind.REASONING,
                ontology.getAxiomCount());
    }

    void releaseLease(ReasonerLease lease) {
        Entry entry = lease.entry();
        if (lease.holdsLock() && entry.useLock.isHeldByCurrentThread()) {
            entry.useLock.unlock();
        }
        entry.release();
        if (lease.oneShot()) {
            entry.retire();
        }
    }

    void retireEntry(Entry entry) {
        if (entry.key != null) {
            reasonerCache.asMap().remove(entry.key, entry);
        }
        entry.retire();
    }

    private Entry startEntry(Key key, OWLOntology ontology, ReasonerType type, Warmup warmup,
                             HeavyWorkAdmission.Hold hold) {
        Entry entry = new Entry(key);
        if (warmup != Warmup.ALL) {
            entry.partialWarmup.set(true);
        }
        try {
            reasonerBuildExecutor.execute(() -> {
                OWLReasoner built = null;
                Throwable failure = null;
                try {
                    built = accounted(switch (warmup) {
                        case ALL -> buildReasonerWithPrecompute(ontology, type);
                        case CLASS_HIERARCHY -> buildClassHierarchyReasoner(ontology, type);
                        case NONE -> createReasoner(ontology, type);
                    }, ontology);
                } catch (Throwable t) {
                    failure = t;
                } finally {
                    hold.close();
                }
                if (failure != null) {
                    entry.future.completeExceptionally(failure);
                } else {
                    entry.future.complete(built);
                }
            });
        } catch (RejectedExecutionException e) {
            hold.close();
            entry.future.completeExceptionally(ReasoningTaskRunner.busy());
        }
        return entry;
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

        OWLReasonerConfiguration config = configurationFor(type);

        try {
            self.research.ontology.common.rules.DisabledRules.strip(ontology);
            OWLOntology target = type == ReasonerType.HERMIT ? SwrlBuiltIns.withoutBuiltInRules(ontology) : ontology;
            return getReasonerFactory(type).createReasoner(target, config);
        } catch (Throwable e) {
            log.error("Failed to create {} reasoner", type, e);
            throw new RuntimeException(type.getDisplayName() + " reasoner could not be started: " + e.getMessage(), e);
        }
    }

    private OWLReasonerConfiguration configurationFor(ReasonerType type) {
        if (type != ReasonerType.HERMIT) {
            return new SimpleConfiguration(reasonerOperationTimeoutMs);
        }
        org.semanticweb.HermiT.Configuration hermit = new org.semanticweb.HermiT.Configuration();
        hermit.ignoreUnsupportedDatatypes = true;
        hermit.individualTaskTimeout = reasonerOperationTimeoutMs;
        return hermit;
    }

    private OWLReasonerFactory getReasonerFactory(ReasonerType type) {
        switch (type) {
            case HERMIT:
                return new ReasonerFactory();

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
        try (ReasonerLease lease = lease(ontology, type)) {
            OWLReasoner reasoner = lease.reasoner();
        
            try {
                boolean consistent = reasoner.isConsistent();
                log.info("Consistency check ({}): {}", type.getDisplayName(), consistent);
                return consistent;
            } catch (Exception e) {
                log.error("Error checking consistency", e);
                throw new RuntimeException("Consistency check failed: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Get all unsatisfiable classes (inconsistent classes)
     */
    public Set<OWLClass> getUnsatisfiableClasses(OWLOntology ontology, ReasonerType type) {
        try (ReasonerLease lease = lease(ontology, type)) {
            OWLReasoner reasoner = lease.reasoner();
        
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
    }

    /**
     * Classify the ontology (compute class hierarchy)
     */
    public void classify(OWLOntology ontology, ReasonerType type) {
        try (ReasonerLease lease = lease(ontology, type)) {
            OWLReasoner reasoner = lease.reasoner();
        
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
    }

    /**
     * Realize the ontology (compute instances for all classes)
     */
    public void realize(OWLOntology ontology, ReasonerType type) {
        try (ReasonerLease lease = lease(ontology, type)) {
            OWLReasoner reasoner = lease.reasoner();
        
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
    }

    /**
     * Get inferred superclasses for a class
     */
    public Set<OWLClass> getInferredSuperClasses(OWLOntology ontology, OWLClass owlClass, ReasonerType type) {
        try (ReasonerLease lease = lease(ontology, type)) {
            OWLReasoner reasoner = lease.reasoner();
        
            try {
                NodeSet<OWLClass> superClasses = reasoner.getSuperClasses(owlClass, false);
                return superClasses.getFlattened();
            } catch (Exception e) {
                log.error("Error getting inferred superclasses", e);
                return Collections.emptySet();
            }
        }
    }

    /**
     * Get inferred subclasses for a class
     */
    public Set<OWLClass> getInferredSubClasses(OWLOntology ontology, OWLClass owlClass, ReasonerType type, boolean direct) {
        try (ReasonerLease lease = lease(ontology, type)) {
            return getInferredSubClasses(lease.reasoner(), owlClass, direct);
        }
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
        try (ReasonerLease lease = lease(ontology, type)) {
            OWLReasoner reasoner = lease.reasoner();
        
            try {
                NodeSet<OWLNamedIndividual> individuals = reasoner.getInstances(owlClass, false);
                return individuals.getFlattened();
            } catch (Exception e) {
                log.error("Error getting inferred instances", e);
                return Collections.emptySet();
            }
        }
    }

    /**
     * Get inferred types for an individual
     */
    public Set<OWLClass> getInferredTypes(OWLOntology ontology, OWLNamedIndividual individual, ReasonerType type) {
        try (ReasonerLease lease = lease(ontology, type)) {
            OWLReasoner reasoner = lease.reasoner();

            CompletableFuture<NodeSet<OWLClass>> future = CompletableFuture.supplyAsync(
                () -> reasoner.getTypes(individual, true), inferredTypesExecutor);
            try {
                NodeSet<OWLClass> types = future.get(PER_INDIVIDUAL_TYPE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                return types.getFlattened();
            } catch (TimeoutException te) {
                log.warn("Timed out computing inferred types for individual {} ({} classes in signature)",
                    individual.getIRI(), ontology.getClassesInSignature().size());
                ReasonerLease abandoned = lease.handOff();
                lease.retire();
                reasoner.interrupt();
                future.whenComplete((result, ex) -> abandoned.close());
                return Collections.emptySet();
            } catch (Exception e) {
                log.error("Error getting inferred types", e);
                return Collections.emptySet();
            }
        }
    }

    /**
     * Get inferred sub object properties for a property
     */
    public Set<OWLObjectPropertyExpression> getInferredSubObjectProperties(OWLOntology ontology, OWLObjectProperty property, ReasonerType type, boolean direct) {
        try (ReasonerLease lease = lease(ontology, type)) {
            OWLReasoner reasoner = lease.reasoner();
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
    }

    public Set<OWLObjectPropertyExpression> getInferredSubObjectProperties(OWLOntology ontology, OWLObjectProperty property, ReasonerType type) {
        return getInferredSubObjectProperties(ontology, property, type, false);
    }

    /**
     * Get inferred sub data properties for a property
     */
    public Set<OWLDataPropertyExpression> getInferredSubDataProperties(OWLOntology ontology, OWLDataProperty property, ReasonerType type, boolean direct) {
        try (ReasonerLease lease = lease(ontology, type)) {
            OWLReasoner reasoner = lease.reasoner();
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

    public Optional<OWLReasoner> findCachedReasoner(OWLOntology ontology) {
        for (ReasonerType type : ReasonerType.values()) {
            Entry entry = reasonerCache.getIfPresent(new Key(ontology, type));
            if (entry != null && entry.future.isDone() && !entry.future.isCompletedExceptionally()) {
                return Optional.of(entry.future.join());
            }
        }
        return Optional.empty();
    }

    public void disposeReasoner(OWLOntology ontology, ReasonerType type) {
        reasonerCache.invalidate(new Key(ontology, type));
        log.info("Disposed {} reasoner for ontology object {}", type.getDisplayName(), System.identityHashCode(ontology));
    }

    public void disposeReasoner(String ontologyId, ReasonerType type) {
        reasonerCache.asMap().keySet().removeIf(key -> {
            OWLOntology candidate = key.ontologyOrNull();
            return key.type == type && candidate != null
                    && candidate.getOntologyID().getOntologyIRI().map(Object::toString).filter(ontologyId::equals).isPresent();
        });
        log.info("Disposed {} reasoner for ontology {}", type.getDisplayName(), ontologyId);
    }

    Entry installEntryForTest(OWLOntology ontology, ReasonerType type) {
        Key key = new Key(ontology, type);
        Entry entry = new Entry(key);
        reasonerCache.put(key, entry);
        return entry;
    }

    Entry cachedEntryForTest(OWLOntology ontology, ReasonerType type) {
        return reasonerCache.getIfPresent(new Key(ontology, type));
    }

    int cachedEntryCount() {
        reasonerCache.cleanUp();
        return (int) reasonerCache.estimatedSize();
    }

    /**
     * Get reasoner statistics
     */
    public Map<String, Object> getReasonerStats(OWLOntology ontology, ReasonerType type) {
        try (ReasonerLease lease = lease(ontology, type)) {
            OWLReasoner reasoner = lease.reasoner();
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
                stats.put("supportsOWL2DL", type == ReasonerType.HERMIT);
            
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

        HeavyWorkAdmission.Hold hold;
        try {
            hold = admitBuild(ontology);
        } catch (BusyException busy) {
            log.warn("Save-time consistency check skipped, the server is busy: {}", busy.getMessage());
            return SaveConsistencyResult.timedOut();
        }
        long setupStart = System.currentTimeMillis();
        CompletableFuture<OWLReasoner> setup;
        try {
            setup = CompletableFuture.supplyAsync(() -> createReasoner(stripSwrlRules(ontology), type),
                    saveConsistencySetupExecutor);
        } catch (RejectedExecutionException ree) {
            log.warn("Too many concurrent save-time reasoner setups; allowing save through unchecked");
            hold.close();
            return SaveConsistencyResult.timedOut();
        }
        OWLReasoner reasoner;
        try {
            reasoner = setup.get(SAVE_CONSISTENCY_SETUP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("Save-time setup (strip+create) took longer than {} ms; allowing save through unchecked",
                    SAVE_CONSISTENCY_SETUP_TIMEOUT_MS);
            setup.whenComplete((r, ex) -> disposeSaveReasoner(r, hold));
            return SaveConsistencyResult.timedOut();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            setup.whenComplete((r, ex) -> disposeSaveReasoner(r, hold));
            return SaveConsistencyResult.timedOut();
        } catch (Exception e) {
            log.warn("Save-time setup failed ({}); allowing save through unchecked", e.getMessage());
            hold.close();
            return SaveConsistencyResult.timedOut();
        }
        long consistencyStart = System.currentTimeMillis();
        CompletableFuture<Boolean> future;
        try {
            future = CompletableFuture.supplyAsync(reasoner::isConsistent, saveConsistencyExecutor);
        } catch (RejectedExecutionException ree) {
            log.warn("Too many concurrent save-time consistency checks; allowing save through unchecked");
            disposeSaveReasoner(reasoner, hold);
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
            future.whenComplete((r, ex) -> disposeSaveReasoner(reasoner, hold));
            return SaveConsistencyResult.timedOut();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            reasoner.interrupt();
            future.whenComplete((r, ex) -> disposeSaveReasoner(reasoner, hold));
            return SaveConsistencyResult.timedOut();
        } catch (Exception e) {
            log.warn("Save-time consistency check failed ({}); allowing save through unchecked", e.getMessage());
            disposeSaveReasoner(reasoner, hold);
            return SaveConsistencyResult.timedOut();
        }

        disposeSaveReasoner(reasoner, hold);
        if (consistent) {
            return SaveConsistencyResult.consistent(Collections.emptyList());
        }
        return SaveConsistencyResult.inconsistent(GENERIC_INCONSISTENCY_MESSAGE, null);
    }

    private static void disposeSaveReasoner(OWLReasoner reasoner, HeavyWorkAdmission.Hold hold) {
        try {
            if (reasoner != null) {
                reasoner.dispose();
            }
        } catch (Exception ignored) {
        } finally {
            hold.close();
        }
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
        HeavyWorkAdmission.Hold hold = admitBuild(reasoningOntology);
        OWLReasoner reasoner;
        try {
            reasoner = createReasoner(reasoningOntology, type);
        } catch (RuntimeException | Error e) {
            hold.close();
            throw e;
        }
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
            disposeSaveReasoner(reasoner, hold);
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
