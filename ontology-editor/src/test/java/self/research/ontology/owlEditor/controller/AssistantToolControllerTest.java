package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import self.research.ontology.owlEditor.dto.ReadContextRequest;
import self.research.ontology.owlEditor.dto.RunSparqlRequest;
import self.research.ontology.owlEditor.service.AssistantContextToolService;
import self.research.ontology.owlEditor.service.AssistantContextToolService.ContextToolResult;
import self.research.ontology.owlEditor.service.AssistantSparqlToolService;
import self.research.ontology.owlEditor.service.AssistantSparqlToolService.SparqlToolResult;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class AssistantToolControllerTest {

    @Mock
    private AssistantSparqlToolService sparqlToolService;

    @Mock
    private AssistantContextToolService contextToolService;

    private AssistantToolController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new AssistantToolController(sparqlToolService, contextToolService);
    }

    @Test
    void runSparqlReturnsUnauthorizedWithoutBearerToken() {
        ResponseEntity<?> response = controller.runSparql(
                "s1", new RunSparqlRequest("SELECT * WHERE { ?s ?p ?o }"), new MockHttpServletRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void runSparqlReturnsOkEnvelopeOnSuccess() {
        when(sparqlToolService.runSparql(anyString(), anyString(), anyString())).thenReturn(
                SparqlToolResult.builder().ok(true).rows(List.of()).truncated(false).rowCount(0).revision(42L).build());

        ResponseEntity<?> response = controller.runSparql(
                "s1", new RunSparqlRequest("SELECT * WHERE { ?s ?p ?o }"), requestWithBearerToken());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(true, body.get("ok"));
    }

    @Test
    void runSparqlReturnsErrorEnvelopeOnFailure() {
        when(sparqlToolService.runSparql(anyString(), anyString(), anyString())).thenReturn(
                SparqlToolResult.builder().ok(false).errorCode("NOT_SELECT_ONLY").message("no").build());

        ResponseEntity<?> response = controller.runSparql(
                "s1", new RunSparqlRequest("DELETE WHERE { ?s ?p ?o }"), requestWithBearerToken());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(false, body.get("ok"));
        assertEquals("NOT_SELECT_ONLY", body.get("errorCode"));
    }

    @Test
    void readContextReturnsUnauthorizedWithoutBearerToken() {
        ResponseEntity<?> response = controller.readContext(
                "s1", new ReadContextRequest(List.of(), "definitions"), new MockHttpServletRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void readContextReturnsOkEnvelopeOnSuccess() {
        when(contextToolService.readContext(anyString(), anyString(), any(), anyString())).thenReturn(
                ContextToolResult.builder().ok(true).items(List.of()).coverage("complete").revision(42L).build());

        ResponseEntity<?> response = controller.readContext(
                "s1", new ReadContextRequest(List.of(), "definitions"), requestWithBearerToken());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals(true, body.get("ok"));
    }

    @Test
    void rateLimitedReadContextBecomesHttp429WithRetryAfterHeaderAndBodyField() {
        when(contextToolService.readContext(anyString(), anyString(), any(), anyString())).thenReturn(
                ContextToolResult.builder().ok(false).errorCode("RATE_LIMITED").message("busy")
                        .retryAfterSeconds(2).build());

        ResponseEntity<?> response = controller.readContext(
                "s1", new ReadContextRequest(List.of(), "definitions"), requestWithBearerToken());

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("2", response.getHeaders().getFirst("Retry-After"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("RATE_LIMITED", body.get("errorCode"));
        assertEquals(2, body.get("retryAfterSeconds"));
        assertEquals(false, body.get("ok"));
    }

    @Test
    void rateLimitedRunSparqlBecomesHttp429WithRetryAfterHeader() {
        when(sparqlToolService.runSparql(anyString(), anyString(), anyString())).thenReturn(
                SparqlToolResult.builder().ok(false).errorCode("RATE_LIMITED").message("busy")
                        .retryAfterSeconds(5).build());

        ResponseEntity<?> response = controller.runSparql(
                "s1", new RunSparqlRequest("SELECT * WHERE { ?s ?p ?o }"), requestWithBearerToken());

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("5", response.getHeaders().getFirst("Retry-After"));
    }

    @Test
    void errorWithNullMessageDoesNotBlowUp() {
        when(sparqlToolService.runSparql(anyString(), anyString(), anyString())).thenReturn(
                SparqlToolResult.builder().ok(false).errorCode("QUERY_ERROR").build());

        ResponseEntity<?> response = controller.runSparql(
                "s1", new RunSparqlRequest("SELECT * WHERE { ?s ?p ?o }"), requestWithBearerToken());

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    private MockHttpServletRequest requestWithBearerToken() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"email\":\"user@example.com\"}".getBytes());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer header." + payload + ".signature");
        return request;
    }
}
