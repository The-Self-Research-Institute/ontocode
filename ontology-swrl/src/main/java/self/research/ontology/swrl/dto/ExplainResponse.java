package self.research.ontology.swrl.dto;

import java.util.List;
import java.util.Map;

public record ExplainResponse(String conclusion, String status, List<Justification> justifications, List<String> notes) {

    public record Justification(String ruleName, String ruleText, Map<String, String> bindings, List<Premise> premises) {
    }

    public record Premise(String kind, String text) {
    }
}
