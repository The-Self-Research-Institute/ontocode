package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.model.Statement;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record Materialization(Set<Statement> inferred, int ruleConclusions, List<String> partialReasons,
                              EqualityMetrics equality, Map<String, String> engineVersions,
                              Map<String, Long> stepMillis) {

    public record EqualityMetrics(int groups, int largestGroup, long triplesAdded, boolean incomplete) {
    }

    public Materialization withoutStatements() {
        return new Materialization(Set.of(), ruleConclusions, partialReasons, equality, engineVersions, stepMillis);
    }

    public boolean partial() {
        return !partialReasons.isEmpty();
    }
}
