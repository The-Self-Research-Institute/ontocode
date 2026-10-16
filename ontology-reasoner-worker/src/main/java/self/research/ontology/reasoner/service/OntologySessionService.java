package self.research.ontology.reasoner.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.NTriplesDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OntologySessionService {

    private static final Logger log = LoggerFactory.getLogger(OntologySessionService.class);

    private static final java.util.concurrent.ConcurrentHashMap<Long, OWLReasoner> ACTIVE_REASONER_BY_THREAD =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void interruptReasonerOnThread(long threadId) {
        OWLReasoner reasoner = ACTIVE_REASONER_BY_THREAD.get(threadId);
        if (reasoner != null) {
            try {
                reasoner.interrupt();
            } catch (Exception ignored) {
            }
        }
    }

    private final EditorClient editorClient;
    private final OntologyCache ontologyCache;
    private final long hermitMaxTriples;
    private final long reasonerTimeoutMs;
    private final long maxTriples;

    public OntologySessionService(EditorClient editorClient,
                                  OntologyCache ontologyCache,
                                  @Value("${ontocode.reasoning.large-triple-threshold:500000}") long hermitMaxTriples,
                                  @Value("${ontocode.reasoning.job-timeout-ms:1800000}") long reasonerTimeoutMs,
                                  @Value("${ontocode.reasoner.max-triples:5000000}") long maxTriples) {
        this.editorClient = editorClient;
        this.ontologyCache = ontologyCache;
        this.hermitMaxTriples = hermitMaxTriples;
        this.reasonerTimeoutMs = reasonerTimeoutMs;
        this.maxTriples = maxTriples;
    }

    public ReasoningSession openSession(String projectId, ReasonerType reasonerType) throws Exception {
        return openSession(projectId, reasonerType, null);
    }

    public ReasoningSession openSession(String projectId, ReasonerType reasonerType, String userId) throws Exception {
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

        if (cacheEligible && revision >= 0) {
            var cached = ontologyCache.getAndBeginUse(projectId, revision);
            if (cached.isPresent()) {
                OWLOntology ontology = cached.get();
                log.info("[Reasoner] Skipping OWL load — reusing cached ontology for {} (revision={})", projectId, revision);
                OWLReasoner reasoner;
                try {
                    reasoner = EphemeralReasonerFactory.create(ontology, effectiveType, reasonerTimeoutMs);
                } catch (RuntimeException e) {
                    ontologyCache.endUse(ontology);
                    throw e;
                }
                return new ReasoningSession(null, ontology, reasoner, effectiveType, downgradedWarning, true, ontologyCache, revision);
            }
        }

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = editorClient.loadOntologyStream(projectId, userId, in -> {
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
        log.info("Loaded ontology for {} ({} axioms, userId={}, reasoner={})",
                projectId, ontology.getAxiomCount(), userId, effectiveType);

        boolean storedInCache = cacheEligible && revision >= 0;
        if (storedInCache) {
            ontologyCache.putAndBeginUse(projectId, revision, manager, ontology);
        }

        OWLReasoner reasoner;
        try {
            reasoner = EphemeralReasonerFactory.create(ontology, effectiveType, reasonerTimeoutMs);
        } catch (RuntimeException e) {
            if (storedInCache) {
                ontologyCache.endUse(ontology);
            }
            throw e;
        }

        return new ReasoningSession(storedInCache ? null : manager, ontology, reasoner, effectiveType,
                downgradedWarning, storedInCache, storedInCache ? ontologyCache : null, revision);
    }

    private boolean isHeavyReasoner(ReasonerType type) {
        return type == ReasonerType.HERMIT
                || type == ReasonerType.OPENLLET
                || type == ReasonerType.PELLET
                || type == ReasonerType.FACTPLUSPLUS;
    }

    public OWLOntology loadForParse(String projectId) {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        return editorClient.loadOntologyStream(projectId, null, in -> {
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

        ReasoningSession(OWLOntologyManager manager, OWLOntology ontology, OWLReasoner reasoner,
                         ReasonerType actualReasonerType, String downgradedWarning, boolean fromCache,
                         OntologyCache cache, long revision) {
            this.manager = manager;
            this.ontology = ontology;
            this.reasoner = reasoner;
            this.actualReasonerType = actualReasonerType;
            this.downgradedWarning = downgradedWarning;
            this.fromCache = fromCache;
            this.cache = cache;
            this.revision = revision;
            ACTIVE_REASONER_BY_THREAD.put(Thread.currentThread().threadId(), reasoner);
        }

        public OWLOntology ontology() { return ontology; }
        public OWLReasoner reasoner() { return reasoner; }
        public ReasonerType actualReasonerType() { return actualReasonerType; }
        public String downgradedWarning() { return downgradedWarning; }
        public long revision() { return revision; }

        @Override
        public void close() {
            ACTIVE_REASONER_BY_THREAD.remove(Thread.currentThread().threadId(), reasoner);
            try {
                reasoner.dispose();
            } catch (Exception ignored) {
            }

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
