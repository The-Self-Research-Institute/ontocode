package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

public final class DisabledRulesHeader {

    public static final String NAME = "X-Ontocode-Disabled-Rules";

    private static final Logger log = LoggerFactory.getLogger(DisabledRulesHeader.class);

    private DisabledRulesHeader() {
    }

    public static HttpHeaders into(HttpHeaders headers, RuleService ruleService, String projectId, boolean draft,
                                   String userId) {
        if (ruleService == null) {
            return headers;
        }
        try {
            headers.set(NAME, String.valueOf(ruleService.disabledRules(projectId, draft, draft ? userId : null).size()));
        } catch (RuntimeException e) {
            log.warn("[EXPORT] Could not count the disabled rules of project {}: {}", projectId, e.getMessage());
        }
        return headers;
    }
}
