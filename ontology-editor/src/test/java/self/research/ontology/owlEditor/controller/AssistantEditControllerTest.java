package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.dto.ProposeEditRequest;
import self.research.ontology.owlEditor.service.AssistantEditApplyService;
import self.research.ontology.owlEditor.service.AssistantEditApplyService.ApplyResult;
import self.research.ontology.owlEditor.service.AssistantEditProposalService;
import self.research.ontology.owlEditor.service.AssistantEditProposalService.ProposeEditResult;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class AssistantEditControllerTest {

    @Mock
    private AssistantEditProposalService proposalService;

    @Mock
    private AssistantEditApplyService applyService;

    private AssistantEditController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new AssistantEditController(proposalService, applyService);
    }

    @Test
    void proposeReturnsUnauthorizedWithoutBearerToken() {
        ResponseEntity<?> response = controller.propose(
                "s1", new ProposeEditRequest(List.of()), new MockHttpServletRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void proposeReturnsOkEnvelopeOnSuccess() {
        when(proposalService.propose(anyString(), anyString(), any())).thenReturn(
                ProposeEditResult.builder().ok(true).groups(List.of()).build());

        ResponseEntity<?> response = controller.propose(
                "s1", new ProposeEditRequest(List.of()), requestWithBearerToken());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(true, body.get("ok"));
    }

    @Test
    void proposeReturnsErrorEnvelopeOnFailure() {
        when(proposalService.propose(anyString(), anyString(), any())).thenReturn(
                ProposeEditResult.builder().ok(false).errorCode("SESSION_NOT_FOUND").message("no").build());

        ResponseEntity<?> response = controller.propose(
                "s1", new ProposeEditRequest(List.of()), requestWithBearerToken());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(false, body.get("ok"));
        assertEquals("SESSION_NOT_FOUND", body.get("errorCode"));
    }

    @Test
    void applyReturnsUnauthorizedWithoutBearerToken() {
        ResponseEntity<?> response = controller.apply("s1", "g1", new MockHttpServletRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void applyReturnsOkEnvelopeOnSuccess() {
        when(applyService.applyGroup(anyString(), anyString(), anyString())).thenReturn(
                ApplyResult.builder().ok(true).applied(true).newRevision(5L).remappedPendingGroups(List.of()).build());

        ResponseEntity<?> response = controller.apply("s1", "g1", requestWithBearerToken());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(true, body.get("ok"));
        assertEquals(5L, body.get("newRevision"));
    }

    @Test
    void applyReturnsErrorEnvelopeOnFailure() {
        when(applyService.applyGroup(anyString(), anyString(), anyString())).thenReturn(
                ApplyResult.builder().ok(false).errorCode("STALE_GROUP").message("stale").build());

        ResponseEntity<?> response = controller.apply("s1", "g1", requestWithBearerToken());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(false, body.get("ok"));
        assertEquals("STALE_GROUP", body.get("errorCode"));
    }

    private MockHttpServletRequest requestWithBearerToken() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"email\":\"user@example.com\"}".getBytes());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer header." + payload + ".signature");
        return request;
    }
}
