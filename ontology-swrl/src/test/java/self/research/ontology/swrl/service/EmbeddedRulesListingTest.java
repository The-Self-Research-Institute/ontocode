package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedRulesListingTest {

    @Test
    void rulesStoredInsideTheOntologyAreListedWithTheirNameAndText() throws Exception {
        TriageHarness harness = new TriageHarness();

        List<SwrlEngineService.EmbeddedRule> rules = harness.service.listEmbeddedRules(TriageHarness.PROJECT, null);

        assertEquals(12, rules.size());
        assertTrue(rules.stream().allMatch(r -> r.name() != null && !r.name().isBlank()));
        assertTrue(rules.stream().allMatch(r -> r.ruleText().contains("->")), rules.toString());
        assertTrue(rules.stream().anyMatch(r -> r.ruleText().contains("Child")), rules.toString());
    }
}
