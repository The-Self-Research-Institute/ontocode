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
import self.research.ontology.owlEditor.service.AssistantSessionService;

import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class AssistantSessionControllerTest {

    @Mock
    private AssistantSessionService sessionService;

    private AssistantSessionController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new AssistantSessionController(sessionService);
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

        when(sessionService.createSession("proj-1", "user@example.com", "/doc.owl", "ask", "why is this inconsistent?"))
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

    /** A JWT whose payload decodes to {"email":"user@example.com"} — signature isn't checked here. */
    private MockHttpServletRequest requestWithBearerToken() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"email\":\"user@example.com\"}".getBytes());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer header." + payload + ".signature");
        return request;
    }
}
