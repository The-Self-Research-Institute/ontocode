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
    private final long maxReasonerTriples;

    public OntologySessionService(EditorClient editorClient,
                                  @Value("${ontocode.reasoner.max-triples:1000000}") long maxReasonerTriples) {
        this.editorClient = editorClient;
        this.maxReasonerTriples = maxReasonerTriples;
    }

    public ReasoningSession openSession(String projectId, ReasonerType reasonerType) throws Exception {
        long tripleCount = editorClient.getTripleCount(projectId);
        if (tripleCount > maxReasonerTriples) {
            throw new IllegalArgumentException(
                    "This ontology is too large for in-memory reasoning (" + tripleCount + " triples). "
                            + "Try the SPARQL tab or a smaller scope.");
        }

        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        try (InputStream in = editorClient.openOntologyStream(projectId)) {
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(in);
            log.info("Loaded ontology for {} ({} axioms)", projectId, ontology.getAxiomCount());
            OWLReasoner reasoner = EphemeralReasonerFactory.create(ontology, reasonerType);
            return new ReasoningSession(manager, ontology, reasoner);
        }
    }

    public OWLOntology loadForParse(String projectId) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        try (InputStream in = editorClient.openOntologyStream(projectId)) {
            return manager.loadOntologyFromOntologyDocument(in);
        }
    }

    public static final class ReasoningSession implements AutoCloseable {
        private final OWLOntologyManager manager;
        private final OWLOntology ontology;
        private final OWLReasoner reasoner;

        ReasoningSession(OWLOntologyManager manager, OWLOntology ontology, OWLReasoner reasoner) {
            this.manager = manager;
            this.ontology = ontology;
            this.reasoner = reasoner;
        }

        public OWLOntology ontology() {
            return ontology;
        }

        public OWLReasoner reasoner() {
            return reasoner;
        }

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
