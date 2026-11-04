package self.research.ontology.swrl.controller;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.swrl.service.OntologyClientService;
import self.research.ontology.swrl.service.SwrlEngineService;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwrlControllerAccessTest {

    private final SwrlEngineService engine = Mockito.mock(SwrlEngineService.class);
    private final OntologyClientService client = Mockito.mock(OntologyClientService.class);
    private final SwrlController controller = new SwrlController();

    SwrlControllerAccessTest() {
        ReflectionTestUtils.setField(controller, "swrlEngineService", engine);
        ReflectionTestUtils.setField(controller, "ontologyClient", client);
        ReflectionTestUtils.setField(controller, "internalToken", "secret");
    }

    private Map<String, Supplier<ResponseEntity<?>>> mongoWrites() {
        return Map.of(
                "create", () -> controller.createRule("p1", Map.of()),
                "update", () -> controller.updateRule("p1", "r1", Map.of()),
                "delete", () -> controller.deleteRule("p1", "r1"),
                "batch create", () -> controller.createRulesBatch("p1", List.of()),
                "batch delete", () -> controller.deleteRulesBatch("p1", List.of("r1")),
                "import", () -> controller.importRules("p1", List.of()),
                "duplicate", () -> controller.duplicateRule("p1", "r1", Map.of("newName", "Copy")));
    }

    @Test
    void everyOldRuleWritingEndpointAnswers410AndPointsToTheEditorAndTouchesNothing() {
        mongoWrites().forEach((name, call) -> {
            ResponseEntity<?> response = call.get();
            assertEquals(410, response.getStatusCode().value(), name);
            assertTrue(String.valueOf(response.getBody()).contains("/api/ontology/p1/rules"), name);
        });

        Mockito.verifyNoInteractions(engine);
        Mockito.verifyNoInteractions(client);
    }

    @Test
    void markingALegacyRuleMigratedNeedsPermissionAndFailsClosed() {
        Mockito.when(client.canWriteRules("p1")).thenReturn(new OntologyClientService.RuleAccess(false, "view-only"));
        assertEquals(403, controller.markLegacyRuleMigrated("p1", "r1").getStatusCode().value());

        Mockito.when(client.canWriteRules("p1")).thenThrow(new IllegalStateException("editor down"));
        assertEquals(503, controller.markLegacyRuleMigrated("p1", "r1").getStatusCode().value());

        Mockito.verifyNoInteractions(engine);
    }

    @Test
    void anAllowedCallerCanMarkALegacyRuleMigrated() {
        Mockito.when(client.canWriteRules("p1")).thenReturn(new OntologyClientService.RuleAccess(true, null));

        assertEquals(200, controller.markLegacyRuleMigrated("p1", "r1").getStatusCode().value());

        Mockito.verify(engine).markMigrated("p1", "r1");
    }

    @Test
    void theLegacyListComesFromTheService() {
        Mockito.when(engine.legacyRules("p1")).thenReturn(List.of());

        assertEquals(200, controller.getLegacyRules("p1").getStatusCode().value());
        Mockito.verify(engine).legacyRules("p1");
    }

    @Test
    void parseRejectsEmptyTextAndAnswersTheServiceResult() {
        assertEquals(400, controller.parseRule("p1", Map.of("ruleName", "r"), false, null).getStatusCode().value());
        assertEquals(400, controller.parseRule("p1", Map.of("ruleText", "x"), true, null).getStatusCode().value());
        Mockito.when(engine.parseRule("p1", "A(?x) -> B(?x)", null))
                .thenReturn(new SwrlEngineService.RuleParse(true, "Ontology()", List.of(), List.of()));

        ResponseEntity<?> response = controller.parseRule("p1", Map.of("ruleName", "r", "ruleText", "A(?x) -> B(?x)"), false, null);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(((SwrlEngineService.RuleParse) response.getBody()).valid());
    }

    @Test
    void theInternalEndpointRefusesAMissingOrWrongToken() throws Exception {
        assertEquals(401, controller.inferOntologyRules("Ontology()", null).getStatusCode().value());
        assertEquals(401, controller.inferOntologyRules("Ontology()", "guess").getStatusCode().value());
        Mockito.verifyNoInteractions(engine);
    }

    @Test
    void theInternalEndpointAcceptsTheRightToken() throws Exception {
        Mockito.when(engine.inferOntologyRules("Ontology()")).thenReturn("Ontology(\n)");

        assertEquals(200, controller.inferOntologyRules("Ontology()", "secret").getStatusCode().value());
    }

    @Test
    void theRulesOwlEndpointRefusesWithoutTheTokenAndSendsHeadersWithIt() throws Exception {
        assertEquals(401, controller.getRulesAsOwl("p1", false, null, null).getStatusCode().value());
        assertEquals(401, controller.getRulesAsOwl("p1", false, null, "guess").getStatusCode().value());
        Mockito.verifyNoInteractions(engine);
        Mockito.when(engine.exportEnabledRulesAsOwl("p1", null)).thenReturn(new SwrlEngineService.RulesExport(
                "Ontology()", List.of(new self.research.ontology.swrl.model.SkippedRule("ré", "bad")), "abc"));

        ResponseEntity<?> response = controller.getRulesAsOwl("p1", false, null, "secret");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("abc", response.getHeaders().getFirst("X-Ontocode-Rules-Fingerprint"));
        assertEquals("[{\"ruleName\":\"r\\u00E9\",\"reason\":\"bad\"}]", response.getHeaders().getFirst("X-Ontocode-Skipped-Rules"));
    }

    @Test
    void aLongSkippedListIsTruncatedWithACount() {
        List<self.research.ontology.swrl.model.SkippedRule> many = new java.util.ArrayList<>();
        for (int i = 0; i < 400; i++) {
            many.add(new self.research.ontology.swrl.model.SkippedRule("rule " + i, "x".repeat(100)));
        }

        String header = SwrlController.skippedRulesHeader(many);

        assertEquals(true, header.length() <= 8192);
        assertEquals(true, header.contains("more skipped rules not listed"));
    }

    @Test
    void executeSelectedReportsSkippedRulesAndCompletion() {
        self.research.ontology.swrl.model.ExecutionResult result = new self.research.ontology.swrl.model.ExecutionResult(true, 5L, 0, List.of(), null);
        result.setSkippedRules(List.of(new self.research.ontology.swrl.model.SkippedRule("theirs", "This rule was not found in this project.")));
        result.setCompletion("completed_with_warnings");
        Mockito.when(engine.executeSelectedRules("p1", List.of("theirs"))).thenReturn(result);

        ResponseEntity<?> response = controller.executeSelectedRules("p1", Map.of("ruleIds", List.of("theirs")));

        self.research.ontology.swrl.dto.ExecutionResponse body = (self.research.ontology.swrl.dto.ExecutionResponse) response.getBody();
        assertEquals("completed_with_warnings", body.getCompletion());
        assertEquals("theirs", body.getSkippedRules().get(0).ruleName());
    }
}
