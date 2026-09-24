package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument.AssistantSessionStatus;
import self.research.ontology.owlEditor.dto.AssistantSessionCreateRequest;
import self.research.ontology.owlEditor.dto.AssistantSessionResponse;
import self.research.ontology.owlEditor.service.AssistantAdmissionLimiter;
import self.research.ontology.owlEditor.service.AssistantSessionService;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantSessionControllerTest {

    @Mock
    private AssistantSessionService sessionService;

    private AssistantSessionController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new AssistantSessionController(sessionService, new AssistantAdmissionLimiter(4, 8, 32, 2, 1));
        when(sessionService.activeSessionLimitRetryAfter(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void createSessionReturnsUnauthorizedWithoutBearerToken() {
        AssistantSessionCreateRequest request = new AssistantSessionCreateRequest();
        request.setProjectId("proj-1");

        ResponseEntity<?> response = controller.createSession(request, new MockHttpServletRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void createSessionReturnsBadRequestWithoutProjectId() {
        AssistantSessionCreateRequest request = new AssistantSessionCreateRequest();

        ResponseEntity<?> response = controller.createSession(request, requestWithBearerToken());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void createSessionReturnsSnapshotAndBudgetOnSuccess() {
        AssistantSessionCreateRequest request = new AssistantSessionCreateRequest();
        request.setProjectId("proj-1");
        request.setDocumentPath("/doc.owl");
        request.setActionType("ask");
        request.setActionContext("why is this inconsistent?");

        when(sessionService.createSession("proj-1", "user@example.com", "/doc.owl", "ask", "why is this inconsistent?",
                null, null))
                .thenReturn(AssistantSessionDocument.builder()
                        .id("session-1")
                        .projectId("proj-1")
                        .documentPath("/doc.owl")
                        .actionType("ask")
                        .pinnedRevision(42L)
                        .status(AssistantSessionStatus.ACTIVE)
                        .retrievalAttemptsRemaining(5)
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build());
        when(sessionService.getMaxRetrievalAttempts()).thenReturn(5);

        ResponseEntity<?> response = controller.createSession(request, requestWithBearerToken());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        AssistantSessionResponse body = assertInstanceOf(AssistantSessionResponse.class, response.getBody());
        assertEquals("session-1", body.getSessionId());
        assertEquals(42L, body.getSnapshot().getRevision());
        assertEquals("proj-1", body.getSnapshot().getProjectId());
        assertEquals(5, body.getBudget().getRetrievalCallsRemaining());
        assertEquals(5, body.getBudget().getMaxRetrievalCalls());
    }

    @Test
    void tooManyActiveSessionsReturns429WithRetryAfterAndCreatesNothing() {
        AssistantSessionCreateRequest request = new AssistantSessionCreateRequest();
        request.setProjectId("proj-1");
        when(sessionService.activeSessionLimitRetryAfter("user@example.com")).thenReturn(Optional.of(37));

        ResponseEntity<?> response = controller.createSession(request, requestWithBearerToken());

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("37", response.getHeaders().getFirst("Retry-After"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("RATE_LIMITED", body.get("errorCode"));
        assertEquals(37, body.get("retryAfterSeconds"));
        verify(sessionService, never()).createSession(any(), any(), any(), any(), any());
        verify(sessionService, never()).createSession(any(), any(), any(), any(), any(), any(), any());
        verify(sessionService).recordCreateRejected("user@example.com", "proj-1", null, null,
                "RATE_LIMITED", "retryAfterSeconds=37");
    }

    @Test
    void providerAndModelArePassedThroughTrimmed() {
        AssistantSessionCreateRequest request = new AssistantSessionCreateRequest();
        request.setProjectId("proj-1");
        request.setProvider(" claude ");
        request.setModel("claude-sonnet-4-5");
        when(sessionService.createSession("proj-1", "user@example.com", null, null, null,
                "claude", "claude-sonnet-4-5"))
                .thenReturn(AssistantSessionDocument.builder().id("s").projectId("proj-1").pinnedRevision(1L)
                        .provider("claude").model("claude-sonnet-4-5")
                        .retrievalAttemptsRemaining(5).expiresAt(Instant.now().plusSeconds(300)).build());

        ResponseEntity<?> response = controller.createSession(request, requestWithBearerToken());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(sessionService).createSession("proj-1", "user@example.com", null, null, null,
                "claude", "claude-sonnet-4-5");
    }

    @Test
    void blankProviderIsTreatedAsAbsentAndOverlongModelIsRejected() {
        AssistantSessionCreateRequest request = new AssistantSessionCreateRequest();
        request.setProjectId("proj-1");
        request.setProvider("   ");
        request.setModel("m".repeat(129));

        ResponseEntity<?> response = controller.createSession(request, requestWithBearerToken());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(sessionService, never()).createSession(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createsBeyondThePerMinuteRateAreRejectedWith429() {
        AssistantSessionCreateRequest request = new AssistantSessionCreateRequest();
        request.setProjectId("proj-1");
        when(sessionService.createSession(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(AssistantSessionDocument.builder().id("s").projectId("proj-1").pinnedRevision(1L)
                        .retrievalAttemptsRemaining(5).expiresAt(Instant.now().plusSeconds(300)).build());

        assertEquals(HttpStatus.OK, controller.createSession(request, requestWithBearerToken()).getStatusCode());
        assertEquals(HttpStatus.OK, controller.createSession(request, requestWithBearerToken()).getStatusCode());
        ResponseEntity<?> third = controller.createSession(request, requestWithBearerToken());

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, third.getStatusCode());
        int retryAfter = Integer.parseInt(third.getHeaders().getFirst("Retry-After"));
        assertTrue(retryAfter >= 1 && retryAfter <= 60);
        verify(sessionService, times(2)).createSession(anyString(), anyString(), any(), any(), any(), any(), any());
    }

    private MockHttpServletRequest requestWithBearerToken() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"email\":\"user@example.com\"}".getBytes());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer header." + payload + ".signature");
        return request;
    }
}
