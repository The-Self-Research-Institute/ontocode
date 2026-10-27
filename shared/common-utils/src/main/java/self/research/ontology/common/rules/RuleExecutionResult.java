package self.research.ontology.common.rules;

import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;

import java.util.List;
import java.util.Map;

public record RuleExecutionResult(
        Status status,
        OWLOntology working,
        List<DerivedFact> derived,
        List<String> executedRules,
        int engineRules,
        int translatedRules,
        List<SkippedRule> skippedRules,
        List<String> limitations,
        String snapshotId,
        Map<String, String> engineVersions,
        Stopping stopping,
        String message) {

    public enum Status {
        COMPLETED("Completed within the supported profile"),
        PARTIAL_RULES_SKIPPED("Partial: some rules were skipped"),
        PARTIAL_ENGINE_UNAVAILABLE("Partial: the rule engine was unavailable"),
        PARTIAL_LIMIT_REACHED("Partial: a round, time or output limit was reached"),
        CANCELLED("Cancelled"),
        INCONSISTENT("Inconsistent"),
        FAILED("Failed");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean complete() {
            return this == COMPLETED;
        }
    }

    public enum Limit {
        NONE, ROUNDS, TIME, DERIVED_FACTS
    }

    public enum Origin {
        RULE_ENGINE, OWL_REASONER
    }

    public record DerivedFact(OWLAxiom axiom, Origin origin, int round) {
    }

    public record SkippedRule(String ruleId, String reason) {
    }

    public record Stopping(int rounds, int maxRounds, long elapsedMillis, long maxMillis, Limit limit, boolean converged) {
    }

    public boolean complete() {
        return status.complete();
    }

    public String consistencyStatement() {
        return switch (status) {
            case INCONSISTENT -> "The ontology is inconsistent" + scope();
            case COMPLETED -> "The ontology and all enabled rules are consistent within the supported rule profile.";
            case CANCELLED, FAILED -> "Consistency was not determined.";
            default -> "The ontology is consistent" + scope() + "; the result is incomplete, so rules that did not run were not checked.";
        };
    }

    private String scope() {
        return " as far as the OWL axioms and the rules that ran can tell";
    }
}
