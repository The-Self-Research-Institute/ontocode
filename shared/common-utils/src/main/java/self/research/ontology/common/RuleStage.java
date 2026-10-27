package self.research.ontology.common;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLRule;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public interface RuleStage {

    Optional<Set<OWLAxiom>> infer(OWLOntology ontology, Collection<SWRLRule> rules);

    default String describe() {
        return "custom rule engine";
    }
}
