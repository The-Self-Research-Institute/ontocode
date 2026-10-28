package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.SWRLRule;
import self.research.ontology.common.rules.DisabledRules;
import self.research.ontology.owlEditor.service.RuleService.RuleInput;
import self.research.ontology.owlEditor.util.OwlAxiomSparqlWriter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleUnannotatedTest {

    static final String MAIN = "urn:graph:main";

    final ObjectMapper mapper = new ObjectMapper();
    RuleTestSupport.Store store;
    RuleService service;
    String listedId;

    @BeforeEach
    void setUp() throws Exception {
        store = new RuleTestSupport.Store();
        store.put(MAIN, RuleTestSupport.parse(RuleTestSupport.seedTurtle(), RDFFormat.TURTLE));
        SwrlRuleClient client = new SwrlRuleClient((method, url, body, token) -> {
            if (url.contains("/rules/embedded")) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", listedId);
                item.put("ruleName", "Hand written");
                item.put("ruleText", "Student>Person");
                item.put("enabled", true);
                return new SwrlRuleClient.Answer(200, mapper.writeValueAsString(List.of(item)));
            }
            if (url.endsWith("/rules/parse")) {
                JsonNode request = mapper.readTree(body);
                String[] parts = request.path("ruleText").asText().split(">");
                Map<String, Object> answer = new HashMap<>();
                answer.put("valid", true);
                answer.put("axiom", RuleTestSupport.functionalRule(parts[0].trim(), parts[1].trim()));
                answer.put("errors", List.of());
                answer.put("suggestions", List.of());
                return new SwrlRuleClient.Answer(200, mapper.writeValueAsString(answer));
            }
            return new SwrlRuleClient.Answer(200, "[]");
        }, "http://swrl", "t");
        service = new RuleService(client, (projectId, draft, user) -> store.ontology(MAIN),
                (projectId, sparql, draft, user, name, origin) -> store.update(MAIN, sparql));
        store.update(MAIN, OwlAxiomSparqlWriter.toInsertData(Set.of(RuleTestSupport.plainRule("Student", "Person"))));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    Set<SWRLRule> rules() {
        return RuleTestSupport.rulesOf(store.ontology(MAIN));
    }

    @Test
    void editingAHandWrittenRuleFoundByItsHashIdWritesTheNewVersionWithAFreshRuleId() throws Exception {
        SWRLRule plain = rules().iterator().next();
        listedId = UnannotatedRules.hashId(plain);

        Map<String, Object> view = service.update("p", listedId, false, null, "U",
                new RuleInput("Renamed", "Student>Person", "now documented", null, null), ChangeOrigin.manual());

        assertEquals(1, rules().size());
        SWRLRule saved = rules().iterator().next();
        String newId = SwrlRuleKeys.ruleId(saved).orElseThrow();
        assertEquals(newId, view.get("id"));
        assertTrue(!newId.startsWith("rule-"));
        assertEquals("Renamed", SwrlRuleKeys.name(saved).orElseThrow());
        assertEquals(plain.getAxiomWithoutAnnotations(), saved.getAxiomWithoutAnnotations());
    }

    @Test
    void anIdTheEditorCannotRecomputeIsResolvedThroughTheListedRuleText() throws Exception {
        listedId = "rule-0123456789abcdef";

        Map<String, Object> view = service.update("p", listedId, false, null, "U",
                new RuleInput(null, null, null, null, Boolean.FALSE), ChangeOrigin.manual());

        assertEquals(1, rules().size());
        SWRLRule saved = rules().iterator().next();
        assertTrue(DisabledRules.isDisabled(saved));
        assertEquals("Hand written", SwrlRuleKeys.name(saved).orElseThrow());
        assertEquals(view.get("id"), SwrlRuleKeys.ruleId(saved).orElseThrow());
    }

    @Test
    void deletingAHandWrittenRuleRemovesItByHashIdAndByListedText() throws Exception {
        listedId = UnannotatedRules.hashId(rules().iterator().next());
        service.delete("p", listedId, false, null, "U", ChangeOrigin.manual());
        assertTrue(rules().isEmpty());

        store.update(MAIN, OwlAxiomSparqlWriter.toInsertData(Set.of(RuleTestSupport.plainRule("Student", "Person"))));
        listedId = "rule-ffffffffffffffff";
        service.delete("p", listedId, false, null, "U", ChangeOrigin.manual());
        assertTrue(rules().isEmpty());
    }

    @Test
    void aHashIdThatMatchesNothingIsNotFound() {
        listedId = "rule-aaaaaaaaaaaaaaaa";
        assertThrows(RuleService.RuleNotFoundException.class,
                () -> service.delete("p", "rule-bbbbbbbbbbbbbbbb", false, null, "U", ChangeOrigin.manual()));
    }

    @Test
    void theAdoptedRuleCanThenBeEditedByItsNewIdWithoutAdoptingAgain() throws Exception {
        listedId = UnannotatedRules.hashId(rules().iterator().next());
        String id = String.valueOf(service.update("p", listedId, false, null, "U",
                new RuleInput("First", null, null, null, null), ChangeOrigin.manual()).get("id"));

        service.update("p", id, false, null, "U", new RuleInput("Second", null, null, null, null), ChangeOrigin.manual());

        assertEquals(1, rules().size());
        assertEquals(id, SwrlRuleKeys.ruleId(rules().iterator().next()).orElseThrow());
        assertEquals("Second", SwrlRuleKeys.name(rules().iterator().next()).orElseThrow());
    }
}
