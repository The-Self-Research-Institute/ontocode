package self.research.ontology.swrl.controller;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.swrl.dto.CreateRuleRequest;
import self.research.ontology.swrl.dto.UpdateRuleRequest;
import self.research.ontology.swrl.service.OntologyClientService;
import self.research.ontology.swrl.service.SwrlEngineService;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SwrlControllerAccessTest {

    private final SwrlEngineService engine = Mockito.mock(SwrlEngineService.class);
    private final OntologyClientService client = Mockito.mock(OntologyClientService.class);
    private final SwrlController controller = new SwrlController();

    SwrlControllerAccessTest() {
        ReflectionTestUtils.setField(controller, "swrlEngineService", engine);
        ReflectionTestUtils.setField(controller, "ontologyClient", client);
        ReflectionTestUtils.setField(controller, "internalToken", "secret");
    }

    private Map<String, Supplier<ResponseEntity<?>>> writes() {
        return Map.of(
                "create", () -> controller.createRule("p1", new CreateRuleRequest()),
                "update", () -> controller.updateRule("p1", "r1", new UpdateRuleRequest()),
                "delete", () -> controller.deleteRule("p1", "r1"),
                "batch create", () -> controller.createRulesBatch("p1", List.of()),
                "batch delete", () -> controller.deleteRulesBatch("p1", List.of("r1")),
                "import", () -> controller.importRules("p1", List.of()),
                "duplicate", () -> controller.duplicateRule("p1", "r1", Map.of("newName", "Copy")));
    }

    @Test
    void aViewerIsRefusedOnEveryRuleChangingEndpointAndNothingIsChanged() {
        Mockito.when(client.canWriteRules("p1")).thenReturn(new OntologyClientService.RuleAccess(false, "view-only"));

        writes().forEach((name, call) -> assertEquals(403, call.get().getStatusCode().value(), name));

        Mockito.verifyNoInteractions(engine);
    }

    @Test
    void aFailedPermissionCheckFailsClosed() {
        Mockito.when(client.canWriteRules("p1")).thenThrow(new IllegalStateException("editor down"));

        writes().forEach((name, call) -> assertEquals(503, call.get().getStatusCode().value(), name));

        Mockito.verifyNoInteractions(engine);
    }

    @Test
    void anAllowedCallerReachesTheService() {
        Mockito.when(client.canWriteRules("p1")).thenReturn(new OntologyClientService.RuleAccess(true, null));

        controller.deleteRule("p1", "r1");

        Mockito.verify(engine).deleteRule("p1", "r1");
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
