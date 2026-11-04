package self.research.ontology.common.rules;

import java.util.function.BooleanSupplier;

public record RuleExecutionLimits(int maxRounds, long maxMillis, int maxDerivedFacts, BooleanSupplier cancelled) {

    public static RuleExecutionLimits defaults() {
        return new RuleExecutionLimits(8, 120_000L, 50_000, () -> false);
    }

    public RuleExecutionLimits withCancel(BooleanSupplier cancel) {
        return new RuleExecutionLimits(maxRounds, maxMillis, maxDerivedFacts, cancel);
    }
}
