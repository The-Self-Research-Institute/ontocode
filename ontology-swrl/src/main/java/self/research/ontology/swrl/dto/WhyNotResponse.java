package self.research.ontology.swrl.dto;

import java.util.List;
import java.util.Map;

public record WhyNotResponse(String ruleName, String individual, String result, String summary,
                             Map<String, String> bindings, List<AtomReport> atoms, String scope, List<String> notes) {

    public record AtomReport(String text, String status, String detail) {
    }
}
