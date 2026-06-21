package self.research.ontology.reasoner.service;

import org.semanticweb.owlapi.apibinding.OWLManager;
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
    private final long hermitMaxTriples;
    private final long elkMaxTriples;

    public OntologySessionService(EditorClient editorClient,
                                  @Value("${ontocode.reasoning.large-triple-threshold:500000}") long hermitMaxTriples,
                                  @Value("${ontocode.reasoner.elk-max-triples:5000000}") long elkMaxTriples) {
        this.editorClient = editorClient;
        this.hermitMaxTriples = hermitMaxTriples;
        this.elkMaxTriples = elkMaxTriples;
    }

    public ReasoningSession openSession(String projectId, ReasonerType reasonerType) throws Exception {
        return openSession(projectId, reasonerType, null);
    }

    public ReasoningSession openSession(String projectId, ReasonerType reasonerType, String userId) throws Exception {
        long tripleCount = editorClient.getTripleCount(projectId);

        if (tripleCount > elkMaxTriples) {
            throw new IllegalArgumentException(
                    "This ontology is too large for in-memory reasoning (" + tripleCount + " triples). "
                            + "Try the SPARQL tab or a smaller scope.");
        }

        ReasonerType effectiveType = reasonerType;
        String downgradedWarning = null;

        if (tripleCount > hermitMaxTriples && isHeavyReasoner(reasonerType)) {
            effectiveType = ReasonerType.ELK;
            downgradedWarning = "Large ontology detected (" + tripleCount + " triples) — automatically switched "
                    + "to ELK for memory efficiency. Some OWL DL axioms (cardinality restrictions, "
                    + "allValuesFrom, complement/union) may not be fully inferred. Results are sound "
                    + "for OWL EL ontologies.";
            log.info("[Reasoner] Auto-downgraded {} → ELK for project {} ({} triples > {} threshold)",
                    reasonerType, projectId, tripleCount, hermitMaxTriples);
        }

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        try (InputStream in = editorClient.openOntologyStream(projectId, userId)) {
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(in);
            log.info("Loaded ontology for {} ({} axioms, userId={}, reasoner={})",
                    projectId, ontology.getAxiomCount(), userId, effectiveType);
            OWLReasoner reasoner = EphemeralReasonerFactory.create(ontology, effectiveType);
            return new ReasoningSession(manager, ontology, reasoner, effectiveType, downgradedWarning);
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
            return manager.loadOntologyFromOntologyDocument(in);
        }
    }

    public static final class ReasoningSession implements AutoCloseable {
        private final OWLOntologyManager manager;
        private final OWLOntology ontology;
        private final OWLReasoner reasoner;
        private final ReasonerType actualReasonerType;
        private final String downgradedWarning;

        ReasoningSession(OWLOntologyManager manager, OWLOntology ontology, OWLReasoner reasoner,
                         ReasonerType actualReasonerType, String downgradedWarning) {
            this.manager = manager;
            this.ontology = ontology;
            this.reasoner = reasoner;
            this.actualReasonerType = actualReasonerType;
            this.downgradedWarning = downgradedWarning;
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
            try {
                manager.removeOntology(ontology);
            } catch (Exception ignored) {
            }
        }
    }
}
