package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import self.research.ontology.owlEditor.controller.RuleController;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class DisabledRulesExportTest {

    final ObjectMapper mapper = new ObjectMapper();

    RuleService serviceWith(List<Map<String, Object>> items) {
        SwrlRuleClient client = new SwrlRuleClient((method, url, body, token) ->
                new SwrlRuleClient.Answer(200, mapper.writeValueAsString(items)), "http://swrl", "t");
        return new RuleService(client, (p, d, u) -> null, (p, s, d, u, n, o) -> {
        });
    }

    static Map<String, Object> item(String id, String name, boolean enabled) {
        return Map.of("id", id, "ruleName", name, "enabled", enabled);
    }

    @Test
    void theExportHeaderCountsOnlyTheDisabledRules() {
        RuleService service = serviceWith(List.of(item("a", "On", true), item("b", "Off", false), item("c", "Off too", false)));

        HttpHeaders headers = DisabledRulesHeader.into(new HttpHeaders(), service, "p", false, null);

        assertEquals("2", headers.getFirst("X-Ontocode-Disabled-Rules"));
    }

    @Test
    void withNoDisabledRulesTheHeaderIsZero() {
        HttpHeaders headers = DisabledRulesHeader.into(new HttpHeaders(), serviceWith(List.of(item("a", "On", true))),
                "p", true, "u1");

        assertEquals("0", headers.getFirst("X-Ontocode-Disabled-Rules"));
    }

    @Test
    void ifTheRuleServiceIsDownTheExportStillWorksWithoutTheHeader() {
        SwrlRuleClient down = new SwrlRuleClient((m, u, b, t) -> {
            throw new java.io.IOException("down");
        }, "http://swrl", "t");
        RuleService service = new RuleService(down, (p, d, u) -> null, (p, s, d, u, n, o) -> {
        });

        HttpHeaders headers = DisabledRulesHeader.into(new HttpHeaders(), service, "p", false, null);

        assertNull(headers.getFirst("X-Ontocode-Disabled-Rules"));
        assertFalse(headers.containsKey("X-Ontocode-Disabled-Rules"));
    }

    @Test
    void theExportCheckListsTheDisabledRulesWithThePlainWarning() {
        RuleController controller = new RuleController(
                serviceWith(List.of(item("a", "On", true), item("b", "Off", false))), new ProjectWriteLockRegistry());

        ResponseEntity<?> response = controller.exportCheck("p", false, null, null);

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(List.of(Map.of("id", "b", "ruleName", "Off")), body.get("disabledRules"));
        assertEquals("Other tools ignore the disabled flag and will run these rules.", body.get("warning"));
    }

    @Test
    void theExportCheckHasNoWarningWhenNothingIsDisabled() {
        RuleController controller = new RuleController(serviceWith(List.of(item("a", "On", true))),
                new ProjectWriteLockRegistry());

        Map<?, ?> body = (Map<?, ?>) controller.exportCheck("p", false, null, null).getBody();

        assertEquals(List.of(), body.get("disabledRules"));
        assertNull(body.get("warning"));
    }
}
