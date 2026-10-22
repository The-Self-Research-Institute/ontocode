package self.research.ontology.reasoner.service;

import openllet.owlapi.OpenlletReasonerFactory;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.SimpleConfiguration;
import org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.manchester.cs.jfact.JFactFactory;

final class EphemeralReasonerFactory {

    private static final Logger log = LoggerFactory.getLogger(EphemeralReasonerFactory.class);

    private EphemeralReasonerFactory() {}

    static OWLReasoner create(OWLOntology ontology, ReasonerType type, long timeoutMs) {
        OWLReasonerConfiguration config = new SimpleConfiguration(timeoutMs);
        ReasonerType effective = type != null ? type : ReasonerType.OPENLLET;
        try {
            return switch (effective) {
                case HERMIT -> {
                    org.semanticweb.HermiT.Configuration hermitConfig = new org.semanticweb.HermiT.Configuration();
                    hermitConfig.ignoreUnsupportedDatatypes = true;
                    hermitConfig.individualTaskTimeout = timeoutMs;
                    yield new ReasonerFactory().createReasoner(ontology, hermitConfig);
                }
                case PELLET, OPENLLET -> OpenlletReasonerFactory.getInstance().createReasoner(ontology, config);
                case FACTPLUSPLUS -> new JFactFactory().createReasoner(ontology, config);
                case ELK -> new ElkReasonerFactory().createReasoner(ontology, config);
                case STRUCTURAL -> new StructuralReasonerFactory().createReasoner(ontology, config);
            };
        } catch (Throwable e) {
            log.error("Reasoner {} could not be started", effective, e);
            throw new RuntimeException(effective.getDisplayName() + " reasoner could not be started: " + e.getMessage(), e);
        }
    }
}
