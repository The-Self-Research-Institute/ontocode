package self.research.ontology.reasoner.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.NTriplesDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import self.research.ontology.common.PreparedOntology;
import self.research.ontology.common.RuleStage;
import self.research.ontology.common.rules.OntologyRulesFingerprint;
import self.research.ontology.common.SwrlBuiltIns;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.SWRLRule;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OntologySessionService {

    private static final Logger log = LoggerFactory.getLogger(OntologySessionService.class);

    private static final ConcurrentHashMap<Long, Set<OWLReasoner>> ACTIVE_REASONERS_BY_THREAD =
            new ConcurrentHashMap<>();

    public static void interruptReasonerOnThread(long threadId) {
        Set<OWLReasoner> reasoners = ACTIVE_REASONERS_BY_THREAD.get(threadId);
        if (reasoners == null) {
            return;
        }
        for (OWLReasoner reasoner : reasoners) {
            try {
                reasoner.interrupt();
            } catch (Exception ignored) {
            }
        }
    }

    private static void register(long threadId, OWLReasoner reasoner) {
        ACTIVE_REASONERS_BY_THREAD.computeIfAbsent(threadId, id -> ConcurrentHashMap.newKeySet()).add(reasoner);
    }

    private static void unregister(long threadId, OWLReasoner reasoner) {
        ACTIVE_REASONERS_BY_THREAD.computeIfPresent(threadId, (id, reasoners) -> {
            reasoners.remove(reasoner);
            return reasoners.isEmpty() ? null : reasoners;
        });
    }

    private final EditorClient editorClient;
    private final OntologyCache ontologyCache;
    private final long hermitMaxTriples;
    private final long reasonerTimeoutMs;
    private final long maxTriples;

    private static final long SAVED_FINGERPRINT_TTL_MS = 5_000L;

    private record CachedSavedPart(String part, long atMs) {}

    private record Merged(OWLOntology ontology, String keyPart, boolean unavailable) {}

    private final ConcurrentHashMap<String, CachedSavedPart> savedParts = new ConcurrentHashMap<>();
    private SavedRulesClient savedRulesClient;

    public OntologySessionService(EditorClient editorClient,
                                  OntologyCache ontologyCache,
                                  long hermitMaxTriples,
                                  long reasonerTimeoutMs,
                                  long maxTriples) {
        this(editorClient, ontologyCache, SavedRulesClient.none(), hermitMaxTriples, reasonerTimeoutMs, maxTriples);
    }

    @Autowired
    public OntologySessionService(EditorClient editorClient,
                                  OntologyCache ontologyCache,
                                  SavedRulesClient savedRulesClient,
                                  @Value("${ontocode.reasoning.large-triple-threshold:500000}") long hermitMaxTriples,
                                  @Value("${ontocode.reasoning.job-timeout-ms:1800000}") long reasonerTimeoutMs,
                                  @Value("${ontocode.reasoner.max-triples:5000000}") long maxTriples) {
        this.savedRulesClient = savedRulesClient;
        this.editorClient = editorClient;
        this.ontologyCache = ontologyCache;
        this.hermitMaxTriples = hermitMaxTriples;
        this.reasonerTimeoutMs = reasonerTimeoutMs;
        this.maxTriples = maxTriples;
    }

    private RuleStage ruleStage = self.research.ontology.common.RestRuleStage.shared();

    void useRuleStage(RuleStage stage) {
        this.ruleStage = stage;
    }

    void useSavedRulesClient(SavedRulesClient client) {
        this.savedRulesClient = client;
        this.savedParts.clear();
    }

    static String combineFingerprint(String embedded, String savedPart) {
        return savedPart == null || savedPart.isEmpty() ? embedded : embedded + "|saved:" + savedPart;
    }

    public long currentRevision(String projectId) {
        return editorClient.getRevision(projectId);
    }

    public long currentTripleCount(String projectId) {
        return editorClient.getTripleCount(projectId);
    }

    public ReasoningSession openSession(String projectId, ReasonerType reasonerType) throws Exception {
        return openSession(projectId, reasonerType, null);
    }

    public ReasoningSession openSession(String projectId, ReasonerType reasonerType, String userId) throws Exception {
        return open(projectId, reasonerType, userId, false);
    }

    public ReasoningSession openSnapshotSession(String projectId, ReasonerType requestedType, String userId) throws Exception {
        return open(projectId, requestedType, userId, true);
    }

    private ReasoningSession open(String projectId, ReasonerType reasonerType, String userId, boolean snapshot)
            throws Exception {
        long tripleCount = editorClient.getTripleCount(projectId);
        log.info("[Reasoner] Triple count for project {}: {}", projectId, tripleCount);

        if (tripleCount < 0) {
            log.warn("[Reasoner] Could not read triple count for {} — refusing to reason over it without a size check", projectId);
            throw new IllegalStateException("Could not determine the triple count for project " + projectId
                    + " — refusing to load it for in-process reasoning without a size check.");
        }
        if (tripleCount > maxTriples) {
            throw new IllegalStateException("Ontology has " + tripleCount + " triples, exceeding the configured "
                    + "limit of " + maxTriples + " triples for in-process reasoning.");
        }

        ReasonerType effectiveType = reasonerType;
        String downgradedWarning = null;

        if (tripleCount > hermitMaxTriples && isHeavyReasoner(reasonerType)) {
            effectiveType = ReasonerType.ELK;
            downgradedWarning = "Large ontology detected (" + tripleCount + " triples) — automatically switched "
                    + "to ELK for memory efficiency. Some OWL DL axioms (cardinality restrictions, "
                    + "allValuesFrom, complement/union) may not be fully inferred. Results are sound "
                    + "for OWL EL ontologies.";
            log.info("[Reasoner] Auto-downgraded {} → ELK for project {} ({} > {} threshold)",
                    reasonerType, projectId, tripleCount, hermitMaxTriples);
        } else if (effectiveType == ReasonerType.ELK && tripleCount > hermitMaxTriples) {

            downgradedWarning = "ELK is running on a large ontology (" + tripleCount + " triples). "
                    + "Results may take several minutes. "
                    + "Note: ELK covers the OWL EL profile — cardinality restrictions, allValuesFrom, "
                    + "and complement/union axioms will not be inferred.";
        }

        boolean cacheEligible = (userId == null || userId.isBlank());
        long revision = cacheEligible ? editorClient.getRevision(projectId) : -1;
        boolean useCache = cacheEligible && revision > 0;

        if (useCache) {
            var cached = ontologyCache.getAndBeginUse(projectId, revision, tripleCount);
            if (cached.isPresent()) {
                OWLOntology ontology = cached.get();
                log.info("[Reasoner] Skipping OWL load — reusing cached ontology for {} (revision={})", projectId, revision);
                try {
                    return startSession(null, ontology, effectiveType, downgradedWarning, true, ontologyCache,
                            revision, snapshot, tripleCount, projectId, userId);
                } catch (RuntimeException e) {
                    ontologyCache.endUse(ontology);
                    throw e;
                }
            }
        }

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = loadOntology(projectId, userId, manager);
        log.info("Loaded ontology for {} ({} axioms, userId={}, reasoner={})",
                projectId, ontology.getAxiomCount(), userId, effectiveType);

        boolean storedInCache = useCache;
        if (storedInCache) {
            ontologyCache.putAndBeginUse(projectId, revision, tripleCount, manager, ontology);
        }

        try {
            return startSession(storedInCache ? null : manager, ontology, effectiveType, downgradedWarning,
                    storedInCache, storedInCache ? ontologyCache : null, revision, snapshot, tripleCount, projectId, userId);
        } catch (RuntimeException e) {
            if (storedInCache) {
                ontologyCache.endUse(ontology);
            }
            throw e;
        }
    }

    private OWLOntology loadOntology(String projectId, String userId, OWLOntologyManager manager) {
        return editorClient.loadOntologyStream(projectId, userId, in -> {
            try {
                StreamDocumentSource source = new StreamDocumentSource(
                        in,
                        IRI.create("urn:ontocode:load:" + projectId),
                        new NTriplesDocumentFormat(),
                        "application/n-triples");
                return manager.loadOntologyFromOntologyDocument(source);
            } catch (org.semanticweb.owlapi.model.OWLOntologyCreationException e) {
                throw new RuntimeException("Failed to load ontology for project " + projectId + ": " + e.getMessage(), e);
            }
        });
    }

    public String currentRulesFingerprint(String projectId, long revision, long tripleCount) {
        if (revision <= 0 || tripleCount < 0) {
            return null;
        }
        try {
            var cached = ontologyCache.getAndBeginUse(projectId, revision, tripleCount);
            if (cached.isPresent()) {
                try {
                    return combineFingerprint(OntologyRulesFingerprint.of(cached.get()), currentSavedPart(projectId));
                } finally {
                    ontologyCache.endUse(cached.get());
                }
            }
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology ontology = loadOntology(projectId, null, manager);
            ontologyCache.putAndBeginUse(projectId, revision, tripleCount, manager, ontology);
            try {
                return combineFingerprint(OntologyRulesFingerprint.of(ontology), currentSavedPart(projectId));
            } finally {
                ontologyCache.endUse(ontology);
            }
        } catch (RuntimeException e) {
            log.warn("[Reasoner] Could not fingerprint the rules of project {}: {}", projectId, e.getMessage());
            return null;
        }
    }

    private String currentSavedPart(String projectId) {
        long now = System.currentTimeMillis();
        CachedSavedPart known = savedParts.get(projectId);
        if (known != null && now - known.atMs() < SAVED_FINGERPRINT_TTL_MS) {
            return known.part();
        }
        Optional<SavedRulesClient.SavedRules> loaded = savedRulesClient.load(projectId, null);
        String part = loaded.isPresent() ? loaded.get().keyPart() : "unavailable";
        savedParts.put(projectId, new CachedSavedPart(part, now));
        return part;
    }

    private Merged mergeSavedRules(OWLOntology base, String projectId, String userId) {
        Optional<SavedRulesClient.SavedRules> loaded = savedRulesClient.load(projectId, userId);
        if (loaded.isEmpty()) {
            return new Merged(base, "unavailable", true);
        }
        SavedRulesClient.SavedRules saved = loaded.get();
        String part = saved.keyPart();
        Set<String> existing = base.importsClosure()
                .flatMap(o -> o.axioms(AxiomType.SWRL_RULE))
                .map(rule -> rule.getAxiomWithoutAnnotations().toString())
                .collect(Collectors.toSet());
        List<SWRLRule> toAdd = saved.rules().stream()
                .filter(rule -> existing.add(rule.getAxiomWithoutAnnotations().toString()))
                .toList();
        if (toAdd.isEmpty()) {
            return new Merged(base, part, false);
        }
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology copy = manager.createOntology();
            Set<OWLAxiom> axioms = base.importsClosure().flatMap(OWLOntology::axioms)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            manager.addAxioms(copy, axioms);
            manager.addAxioms(copy, toAdd);
            return new Merged(copy, part, false);
        } catch (Exception e) {
            log.warn("[Reasoner] Could not merge saved SWRL rules into the ontology: {}", e.toString());
            return new Merged(base, "unavailable", true);
        }
    }

    private PreparedOntology prepareRules(OWLOntology ontology, long revision, long tripleCount, String savedPart) {
        String snapshotId = revision + ":" + tripleCount + (savedPart == null || savedPart.isEmpty() ? "" : ":" + savedPart);
        return SwrlBuiltIns.prepareChained(ontology, ruleStage,
                input -> new org.semanticweb.HermiT.ReasonerFactory().createReasoner(input), "HermiT 1.4.5.519",
                snapshotId);
    }

    private ReasoningSession startSession(OWLOntologyManager manager, OWLOntology ontology, ReasonerType type,
                                          String downgradedWarning, boolean fromCache, OntologyCache cache,
                                          long revision, boolean snapshot, long tripleCount,
                                          String projectId, String userId) {
        PreparedOntology prepared = null;
        Merged merged = null;
        if (type == ReasonerType.HERMIT) {
            merged = mergeSavedRules(ontology, projectId, userId);
            prepared = prepareRules(merged.ontology(), revision, tripleCount, merged.keyPart());
            if (prepared.note() != null) {
                downgradedWarning = downgradedWarning == null ? prepared.note() : downgradedWarning + " " + prepared.note();
            }
            if (merged.unavailable()) {
                downgradedWarning = downgradedWarning == null ? SavedRulesClient.UNAVAILABLE_NOTE
                        : downgradedWarning + " " + SavedRulesClient.UNAVAILABLE_NOTE;
            }
        }
        boolean rulesComplete = prepared == null || (prepared.complete() && !merged.unavailable());
        String savedKeyPart = merged == null ? "" : merged.keyPart();
        OWLOntology reasoningInput = prepared != null ? prepared.forHermit() : ontology;
        if (!snapshot) {
            OWLReasoner reasoner = createRegisteredReasoner(reasoningInput, type);
            return new ReasoningSession(manager, ontology, reasoner, type, downgradedWarning, fromCache, cache, revision,
                    null, type, tripleCount, rulesComplete).withSavedRulesKeyPart(savedKeyPart);
        }

        ReasonerType classType = type;
        OWLReasoner classReasoner;
        try {
            classReasoner = createRegisteredReasoner(reasoningInput, classType);
        } catch (RuntimeException e) {
            if (classType != ReasonerType.HERMIT || e instanceof ReasonerInterruptedException
                    || Thread.currentThread().isInterrupted()) {
                throw e;
            }
            String reason = rootMessage(e);
            log.warn("[Reasoner] HermiT could not be started ({}), using JFact for this snapshot", reason);
            classType = ReasonerType.FACTPLUSPLUS;
            classReasoner = createRegisteredReasoner(ontology, classType);
            String fallback = "HermiT could not be started for this ontology (" + reason + "), so JFact was used instead.";
            downgradedWarning = downgradedWarning == null ? fallback : downgradedWarning + " " + fallback;
        }

        ReasonerType propertyType = classType;
        OWLReasoner propertyReasoner = null;
        if (classType == ReasonerType.ELK && tripleCount <= hermitMaxTriples) {
            try {
                Merged propertyMerged = merged != null ? merged : mergeSavedRules(ontology, projectId, userId);
                PreparedOntology forProperty = prepared != null ? prepared
                        : prepareRules(propertyMerged.ontology(), revision, tripleCount, propertyMerged.keyPart());
                if (merged == null) {
                    savedKeyPart = propertyMerged.keyPart();
                }
                propertyReasoner = createRegisteredReasoner(propertyHierarchyInput(forProperty.forHermit()), ReasonerType.HERMIT);
                propertyType = ReasonerType.HERMIT;
                rulesComplete = rulesComplete && forProperty.complete() && !propertyMerged.unavailable();
                if (prepared == null && forProperty.note() != null) {
                    downgradedWarning = downgradedWarning == null ? forProperty.note() : downgradedWarning + " " + forProperty.note();
                }
                if (prepared == null && propertyMerged.unavailable()) {
                    downgradedWarning = downgradedWarning == null ? SavedRulesClient.UNAVAILABLE_NOTE
                            : downgradedWarning + " " + SavedRulesClient.UNAVAILABLE_NOTE;
                }
            } catch (ReasonerInterruptedException e) {
                closeReasoner(classReasoner);
                throw e;
            } catch (RuntimeException e) {
                log.warn("[Reasoner] HermiT could not be started for the property hierarchies, using ELK: {}",
                        e.getMessage());
            }
        }
        return new ReasoningSession(manager, ontology, classReasoner, classType, downgradedWarning, fromCache, cache,
                revision, propertyReasoner, propertyType, tripleCount, rulesComplete).withSavedRulesKeyPart(savedKeyPart);
    }

    static OWLOntology propertyHierarchyInput(OWLOntology source) {
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology target = manager.createOntology();
            manager.addAxioms(target, source.getAxioms().stream()
                    .filter(axiom -> axiom.isOfType(AxiomType.DECLARATION, AxiomType.OBJECT_PROPERTY_DOMAIN,
                            AxiomType.OBJECT_PROPERTY_RANGE, AxiomType.DATA_PROPERTY_DOMAIN, AxiomType.DATA_PROPERTY_RANGE)
                            || AxiomType.RBoxAxiomTypes.contains(axiom.getAxiomType())));
            return target;
        } catch (org.semanticweb.owlapi.model.OWLOntologyCreationException e) {
            throw new RuntimeException("Could not prepare the property hierarchy input: " + e.getMessage(), e);
        }
    }

    static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    OWLReasoner createReasoner(OWLOntology ontology, ReasonerType type) {
        return EphemeralReasonerFactory.create(ontology, type, reasonerTimeoutMs);
    }

    private OWLReasoner createRegisteredReasoner(OWLOntology ontology, ReasonerType type) {
        throwIfInterrupted();
        OWLReasoner reasoner = createReasoner(ontology, type);
        long threadId = Thread.currentThread().threadId();
        register(threadId, reasoner);
        if (Thread.currentThread().isInterrupted()) {
            closeReasoner(reasoner);
            throw new ReasonerInterruptedException("Reasoning was stopped before it started");
        }
        return reasoner;
    }

    private static void closeReasoner(OWLReasoner reasoner) {
        if (reasoner == null) {
            return;
        }
        unregister(Thread.currentThread().threadId(), reasoner);
        try {
            reasoner.dispose();
        } catch (Exception ignored) {
        }
    }

    static void throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new ReasonerInterruptedException("Reasoning was stopped");
        }
    }

    private boolean isHeavyReasoner(ReasonerType type) {
        return type == ReasonerType.HERMIT
                || type == ReasonerType.FACTPLUSPLUS;
    }

    public OWLOntology loadForParse(String projectId) {
        return loadForParse(projectId, null);
    }

    public OWLOntology loadForParse(String projectId, String userId) {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        return editorClient.loadOntologyStream(projectId, userId, in -> {
            try {
                StreamDocumentSource source = new StreamDocumentSource(
                        in,
                        IRI.create("urn:ontocode:load:" + projectId),
                        new NTriplesDocumentFormat(),
                        "application/n-triples");
                return manager.loadOntologyFromOntologyDocument(source);
            } catch (org.semanticweb.owlapi.model.OWLOntologyCreationException e) {
                throw new RuntimeException("Failed to load ontology for project " + projectId + ": " + e.getMessage(), e);
            }
        });
    }

    public static final class ReasoningSession implements AutoCloseable {
        private final OWLOntologyManager manager;
        private final OWLOntology ontology;
        private final OWLReasoner reasoner;
        private final ReasonerType actualReasonerType;
        private final String downgradedWarning;
        private final boolean fromCache;
        private final OntologyCache cache;
        private final long revision;
        private final OWLReasoner propertyReasoner;
        private final ReasonerType propertyReasonerType;
        private final long tripleCount;
        private final boolean rulesComplete;
        private String savedRulesKeyPart = "";

        ReasoningSession(OWLOntologyManager manager, OWLOntology ontology, OWLReasoner reasoner,
                         ReasonerType actualReasonerType, String downgradedWarning, boolean fromCache,
                         OntologyCache cache, long revision) {
            this(manager, ontology, reasoner, actualReasonerType, downgradedWarning, fromCache, cache, revision,
                    null, actualReasonerType);
        }

        ReasoningSession(OWLOntologyManager manager, OWLOntology ontology, OWLReasoner reasoner,
                         ReasonerType actualReasonerType, String downgradedWarning, boolean fromCache,
                         OntologyCache cache, long revision,
                         OWLReasoner propertyReasoner, ReasonerType propertyReasonerType) {
            this(manager, ontology, reasoner, actualReasonerType, downgradedWarning, fromCache, cache, revision,
                    propertyReasoner, propertyReasonerType, -1);
        }

        ReasoningSession(OWLOntologyManager manager, OWLOntology ontology, OWLReasoner reasoner,
                         ReasonerType actualReasonerType, String downgradedWarning, boolean fromCache,
                         OntologyCache cache, long revision,
                         OWLReasoner propertyReasoner, ReasonerType propertyReasonerType, long tripleCount) {
            this(manager, ontology, reasoner, actualReasonerType, downgradedWarning, fromCache, cache, revision,
                    propertyReasoner, propertyReasonerType, tripleCount, true);
        }

        ReasoningSession(OWLOntologyManager manager, OWLOntology ontology, OWLReasoner reasoner,
                         ReasonerType actualReasonerType, String downgradedWarning, boolean fromCache,
                         OntologyCache cache, long revision,
                         OWLReasoner propertyReasoner, ReasonerType propertyReasonerType, long tripleCount,
                         boolean rulesComplete) {
            this.manager = manager;
            this.ontology = ontology;
            this.reasoner = reasoner;
            this.actualReasonerType = actualReasonerType;
            this.downgradedWarning = downgradedWarning;
            this.fromCache = fromCache;
            this.cache = cache;
            this.revision = revision;
            this.propertyReasoner = propertyReasoner;
            this.propertyReasonerType = propertyReasonerType != null ? propertyReasonerType : actualReasonerType;
            this.tripleCount = tripleCount;
            this.rulesComplete = rulesComplete;
        }

        public OWLOntology ontology() { return ontology; }
        public OWLReasoner reasoner() { return reasoner; }
        public OWLReasoner propertyReasoner() { return propertyReasoner; }
        public ReasonerType actualReasonerType() { return actualReasonerType; }
        public ReasonerType propertyReasonerType() { return propertyReasonerType; }
        public String downgradedWarning() { return downgradedWarning; }
        public long revision() { return revision; }
        public long tripleCount() { return tripleCount; }
        public boolean rulesComplete() { return rulesComplete; }
        public String savedRulesKeyPart() { return savedRulesKeyPart; }

        ReasoningSession withSavedRulesKeyPart(String part) {
            this.savedRulesKeyPart = part == null ? "" : part;
            return this;
        }

        @Override
        public void close() {
            closeReasoner(propertyReasoner);
            closeReasoner(reasoner);

            if (fromCache) {
                if (cache != null) {
                    cache.endUse(ontology);
                }
                return;
            }

            if (manager != null) {
                try {
                    manager.removeOntology(ontology);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
