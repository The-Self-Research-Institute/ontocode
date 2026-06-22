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

import java.io.InputStream;

@Service
public class OntologySessionService {

    private static final Logger log = LoggerFactory.getLogger(OntologySessionService.class);

    private final EditorClient editorClient;
    private final OntologyCache ontologyCache;
    private final long hermitMaxTriples;
    private final long elkMaxTriples;

    public OntologySessionService(EditorClient editorClient,
                                  OntologyCache ontologyCache,
                                  @Value("${ontocode.reasoning.large-triple-threshold:500000}") long hermitMaxTriples,
                                  @Value("${ontocode.reasoner.elk-max-triples:5000000}") long elkMaxTriples) {
        this.editorClient = editorClient;
        this.ontologyCache = ontologyCache;
        this.hermitMaxTriples = hermitMaxTriples;
        this.elkMaxTriples = elkMaxTriples;
    }

    public ReasoningSession openSession(String projectId, ReasonerType reasonerType) throws Exception {
        return openSession(projectId, reasonerType, null);
    }

    public ReasoningSession openSession(String projectId, ReasonerType reasonerType, String userId) throws Exception {
        long tripleCount = editorClient.getTripleCount(projectId);
        log.info("[Reasoner] Triple count for project {}: {}", projectId, tripleCount);

        // -1 means the editor couldn't be reached; treat as large to avoid running HermiT blind
        boolean tripleCountUnknown = tripleCount < 0;
        if (tripleCountUnknown) {
            log.warn("[Reasoner] Could not read triple count for {} — assuming large ontology, forcing ELK", projectId);
        }

        if (tripleCount > elkMaxTriples) {
            throw new IllegalArgumentException(
                    "This ontology is too large for in-memory reasoning (" + tripleCount + " triples). "
                            + "Try the SPARQL tab or a smaller scope.");
        }

        ReasonerType effectiveType = reasonerType;
        String downgradedWarning = null;

        if ((tripleCountUnknown || tripleCount > hermitMaxTriples) && isHeavyReasoner(reasonerType)) {
            effectiveType = ReasonerType.ELK;
            String sizeDesc = tripleCountUnknown ? "unknown size" : tripleCount + " triples";
            downgradedWarning = "Large ontology detected (" + sizeDesc + ") — automatically switched "
                    + "to ELK for memory efficiency. Some OWL DL axioms (cardinality restrictions, "
                    + "allValuesFrom, complement/union) may not be fully inferred. Results are sound "
                    + "for OWL EL ontologies.";
            log.info("[Reasoner] Auto-downgraded {} → ELK for project {} ({} > {} threshold or size unknown)",
                    reasonerType, projectId, sizeDesc, hermitMaxTriples);
        }

        // Cache lookup: only for main-graph exports (draft overlay is user-specific, skip caching)
        boolean cacheEligible = (userId == null || userId.isBlank());
        long revision = cacheEligible ? editorClient.getRevision(projectId) : -1;

        if (cacheEligible && revision >= 0) {
            var cached = ontologyCache.get(projectId, revision);
            if (cached.isPresent()) {
                OWLOntology ontology = cached.get();
                log.info("[Reasoner] Skipping OWL load — reusing cached ontology for {} (revision={})", projectId, revision);
                OWLReasoner reasoner = EphemeralReasonerFactory.create(ontology, effectiveType);
                return new ReasoningSession(null, ontology, reasoner, effectiveType, downgradedWarning, true);
            }
        }

        // Fresh load
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        try (InputStream in = editorClient.openOntologyStream(projectId, userId)) {
            // Explicit N-Triples format — avoids OWLAPI autodetect overhead and picks the fastest parser
            StreamDocumentSource source = new StreamDocumentSource(
                    in,
                    IRI.create("urn:ontocode:load:" + projectId),
                    new NTriplesDocumentFormat(),
                    "application/n-triples");
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(source);
            log.info("Loaded ontology for {} ({} axioms, userId={}, reasoner={})",
                    projectId, ontology.getAxiomCount(), userId, effectiveType);

            if (cacheEligible && revision >= 0) {
                ontologyCache.put(projectId, revision, manager, ontology);
            }

            OWLReasoner reasoner = EphemeralReasonerFactory.create(ontology, effectiveType);
            return new ReasoningSession(manager, ontology, reasoner, effectiveType, downgradedWarning, false);
        }
    }

    private boolean isHeavyReasoner(ReasonerType type) {
        return type == ReasonerType.HERMIT
                || type == ReasonerType.OPENLLET
                || type == ReasonerType.PELLET
                || type == ReasonerType.FACTPLUSPLUS;
    }

    public OWLOntology loadForParse(String projectId) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        try (InputStream in = editorClient.openOntologyStream(projectId, null)) {
            StreamDocumentSource source = new StreamDocumentSource(
                    in,
                    IRI.create("urn:ontocode:load:" + projectId),
                    new NTriplesDocumentFormat(),
                    "application/n-triples");
            return manager.loadOntologyFromOntologyDocument(source);
        }
    }

    public static final class ReasoningSession implements AutoCloseable {
        private final OWLOntologyManager manager;
        private final OWLOntology ontology;
        private final OWLReasoner reasoner;
        private final ReasonerType actualReasonerType;
        private final String downgradedWarning;
        private final boolean fromCache;

        ReasoningSession(OWLOntologyManager manager, OWLOntology ontology, OWLReasoner reasoner,
                         ReasonerType actualReasonerType, String downgradedWarning, boolean fromCache) {
            this.manager = manager;
            this.ontology = ontology;
            this.reasoner = reasoner;
            this.actualReasonerType = actualReasonerType;
            this.downgradedWarning = downgradedWarning;
            this.fromCache = fromCache;
        }

        public OWLOntology ontology() { return ontology; }
        public OWLReasoner reasoner() { return reasoner; }
        public ReasonerType actualReasonerType() { return actualReasonerType; }
        public String downgradedWarning() { return downgradedWarning; }

        @Override
        public void close() {
            try {
                reasoner.dispose();
            } catch (Exception ignored) {
            }
            // When loaded from cache, the ontology lives in OntologyCache — don't remove it here
            if (!fromCache && manager != null) {
                try {
                    manager.removeOntology(ontology);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
