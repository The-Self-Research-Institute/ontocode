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

    static OWLReasoner create(OWLOntology ontology, ReasonerType type) {
        OWLReasonerConfiguration config = new SimpleConfiguration();
        ReasonerType effective = type != null ? type : ReasonerType.OPENLLET;
        try {
            return switch (effective) {
                case HERMIT -> {
                    try {
                        yield new ReasonerFactory().createReasoner(ontology, config);
                    } catch (NoSuchMethodError e) {
                        log.warn("HermiT unavailable, using Openllet");
                        yield OpenlletReasonerFactory.getInstance().createReasoner(ontology, config);
                    }
                }
                case PELLET, OPENLLET -> OpenlletReasonerFactory.getInstance().createReasoner(ontology, config);
                case FACTPLUSPLUS -> new JFactFactory().createReasoner(ontology, config);
                case ELK -> {
                    try {
                        yield new ElkReasonerFactory().createReasoner(ontology, config);
                    } catch (Exception e) {
                        yield new StructuralReasonerFactory().createReasoner(ontology, config);
                    }
                }
                case STRUCTURAL -> new StructuralReasonerFactory().createReasoner(ontology, config);
            };
        } catch (Exception e) {
            log.warn("Reasoner {} failed, using Structural: {}", effective, e.getMessage());
            return new StructuralReasonerFactory().createReasoner(ontology, config);
        }
    }
}
