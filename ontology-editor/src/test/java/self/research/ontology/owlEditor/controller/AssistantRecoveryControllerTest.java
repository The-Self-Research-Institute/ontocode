package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.service.AssistantRecoveryService;
import self.research.ontology.owlEditor.service.AssistantRecoveryService.RecoveryOutcome;
import self.research.ontology.owlEditor.service.AssistantRecoveryService.RecoveryState;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantRecoveryControllerTest {

    @Mock
    private AssistantRecoveryService recoveryService;

    private AssistantRecoveryController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new AssistantRecoveryController(recoveryService);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    @Test
    void everyEndpointRequiresAnIdentity() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.state("proj-1", new MockHttpServletRequest()).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.restore("proj-1", new MockHttpServletRequest()).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.clear("proj-1", new MockHttpServletRequest()).getStatusCode());
        verify(recoveryService, never()).restore(anyString(), anyString());
        verify(recoveryService, never()).clear(anyString(), anyString());
    }

    @Test
    void stateOfLockedProjectFollowsTheContract() {
        Instant lockedAt = Instant.parse("2026-09-24T10:15:30Z");
        when(recoveryService.state("proj-1"))
                .thenReturn(new RecoveryState(true, "apply failed", lockedAt, "op-1", true));

        ResponseEntity<?> response = controller.state("proj-1", withIdentity());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = body(response);
        assertEquals(true, body.get("locked"));
        assertEquals("apply failed", body.get("reason"));
        assertEquals("2026-09-24T10:15:30Z", body.get("lockedAt"));
        assertEquals("op-1", body.get("operationId"));
        assertEquals(true, body.get("canRestore"));
    }

    @Test
    void stateOfUnlockedProjectOmitsOptionalFields() {
        when(recoveryService.state("proj-1")).thenReturn(new RecoveryState(false, null, null, null, false));

        Map<String, Object> body = body(controller.state("proj-1", withIdentity()));

        assertEquals(Map.of("locked", false, "canRestore", false), body);
    }

    @Test
    void successfulRestoreReturnsOk() {
        when(recoveryService.restore("proj-1", "user@example.com")).thenReturn(new RecoveryOutcome(true, null, null));

        ResponseEntity<?> response = controller.restore("proj-1", withIdentity());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of("ok", true), body(response));
    }

    @Test
    void failedRestoreReturnsConflictWithRecoveryRequired() {
        when(recoveryService.restore("proj-1", "user@example.com"))
                .thenReturn(new RecoveryOutcome(false, "RECOVERY_REQUIRED", "no snapshot"));

        ResponseEntity<?> response = controller.restore("proj-1", withIdentity());

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        Map<String, Object> body = body(response);
        assertEquals(false, body.get("ok"));
        assertEquals("RECOVERY_REQUIRED", body.get("errorCode"));
        assertEquals("no snapshot", body.get("message"));
    }

    @Test
    void successfulClearReturnsOk() {
        when(recoveryService.clear("proj-1", "user@example.com")).thenReturn(new RecoveryOutcome(true, null, null));

        ResponseEntity<?> response = controller.clear("proj-1", withIdentity());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Map.of("ok", true), body(response));
    }

    @Test
    void clearFailureIsAServerError() {
        when(recoveryService.clear("proj-1", "user@example.com"))
                .thenReturn(new RecoveryOutcome(false, "CLEAR_FAILED", "mongo down"));

        ResponseEntity<?> response = controller.clear("proj-1", withIdentity());

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("CLEAR_FAILED", body(response).get("errorCode"));
        assertFalse((Boolean) body(response).get("ok"));
    }

    private MockHttpServletRequest withIdentity() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"email\":\"user@example.com\"}".getBytes());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer header." + payload + ".signature");
        return request;
    }
}
