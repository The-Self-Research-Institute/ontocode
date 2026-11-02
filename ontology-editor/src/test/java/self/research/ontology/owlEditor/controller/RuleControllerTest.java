package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import self.research.ontology.owlEditor.service.ChangeOrigin;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.RuleMigrationService;
import self.research.ontology.owlEditor.service.RuleService;
import self.research.ontology.owlEditor.service.SwrlRuleClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuleControllerTest {

    private RuleService service;
    private RuleController controller;

    @BeforeEach
    void setUp() {
        service = mock(RuleService.class);
        controller = new RuleController(service, new ProjectWriteLockRegistry());
    }

    private RuleController.RuleRequest request() {
        return new RuleController.RuleRequest("R", "A>B", null, null, null, "u1", "U", null, null);
    }

    @Test
    void anInvalidRuleAnswers400WithTheFriendlyErrors() throws Exception {
        when(service.create(anyString(), anyBoolean(), any(), any(), any(), any(ChangeOrigin.class)))
                .thenThrow(new RuleService.InvalidRuleException("bad", List.of("e1"), List.of("s1")));

        ResponseEntity<?> response = controller.create("p", true, request(), null);

        assertEquals(400, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(List.of("e1"), body.get("errors"));
        assertEquals(List.of("s1"), body.get("suggestions"));
    }

    @Test
    void aDuplicateNameAnswers409WithAPlainMessage() throws Exception {
        when(service.create(anyString(), anyBoolean(), any(), any(), any(), any(ChangeOrigin.class)))
                .thenThrow(new RuleService.RuleNameTakenException("R"));

        ResponseEntity<?> response = controller.create("p", true, request(), null);

        assertEquals(409, response.getStatusCode().value());
        assertTrue(String.valueOf(((Map<?, ?>) response.getBody()).get("error")).contains("already exists"));
    }

    @Test
    void anUnknownRuleAnswers404() throws Exception {
        when(service.update(anyString(), eq("x"), anyBoolean(), any(), any(), any(), any(ChangeOrigin.class)))
                .thenThrow(new RuleService.RuleNotFoundException("x"));

        assertEquals(404, controller.update("p", "x", true, request(), null).getStatusCode().value());
    }

    @Test
    void anUnreachableRuleServiceAnswers503() {
        when(service.list(anyString(), anyBoolean(), any())).thenThrow(new SwrlRuleClient.UnavailableException("down"));

        assertEquals(503, controller.list("p", false, null, null).getStatusCode().value());
    }

    @Test
    void aSavedRuleIsReturnedWithItsFields() throws Exception {
        when(service.create(anyString(), anyBoolean(), any(), any(), any(), any(ChangeOrigin.class)))
                .thenReturn(Map.of("id", "abc", "ruleName", "R"));

        ResponseEntity<?> response = controller.create("p", true, request(), null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("abc", ((Map<?, ?>) ((Map<?, ?>) response.getBody()).get("rule")).get("id"));
    }

    @Test
    void theListCarriesTheRulesAndTheRowsThatCouldNotBeMoved() {
        RuleMigrationService migration = mock(RuleMigrationService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "migrationService", migration);
        when(migration.ensureMigrated("p")).thenReturn(List.of(new RuleMigrationService.Unmigrated("l1", "Old", "text", "bad")));
        when(service.list(anyString(), anyBoolean(), any())).thenReturn(List.of(Map.of("id", "r1")));

        Map<?, ?> body = (Map<?, ?>) controller.list("p", false, null, null).getBody();

        assertEquals(List.of(Map.of("id", "r1")), body.get("rules"));
        assertEquals(1, ((List<?>) body.get("unmigrated")).size());
    }

    @Test
    void theMigrateEndpointReportsWhatMovedAndWhatDidNot() throws Exception {
        RuleMigrationService migration = mock(RuleMigrationService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "migrationService", migration);
        when(migration.migrate("p")).thenReturn(new RuleMigrationService.Report(3, List.of("Dup"), List.of()));

        Map<?, ?> body = (Map<?, ?>) controller.migrate("p", null).getBody();

        assertEquals(3, body.get("migrated"));
        assertEquals(List.of("Dup"), body.get("duplicates"));
    }

    @Test
    void creatingARuleRunsTheMigrationFirst() throws Exception {
        RuleMigrationService migration = mock(RuleMigrationService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "migrationService", migration);
        when(service.create(anyString(), anyBoolean(), any(), any(), any(), any(ChangeOrigin.class)))
                .thenReturn(Map.of("id", "abc"));

        controller.create("p", false, request(), null);

        org.mockito.Mockito.verify(migration).ensureMigrated("p");
    }
}
