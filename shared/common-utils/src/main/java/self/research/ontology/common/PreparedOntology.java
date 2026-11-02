package self.research.ontology.common;

import org.semanticweb.owlapi.model.OWLOntology;
import self.research.ontology.common.rules.RuleExecutionResult;

public record PreparedOntology(OWLOntology forHermit, int translated, int engineRules, int leftOut, String note,
                               RuleExecutionResult execution) {

    public PreparedOntology(OWLOntology forHermit, int translated, int engineRules, int leftOut, String note) {
        this(forHermit, translated, engineRules, leftOut, note, null);
    }

    public boolean changed() {
        return note != null;
    }

    public boolean complete() {
        return execution != null ? execution.complete() : leftOut == 0;
    }
}
