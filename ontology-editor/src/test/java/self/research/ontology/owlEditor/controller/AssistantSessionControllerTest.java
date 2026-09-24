package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.dto.AssistantSessionCreateRequest;
import self.research.ontology.owlEditor.dto.AssistantSessionResponse;
import self.research.ontology.owlEditor.repository.AssistantSessionRepository;
import self.research.ontology.owlEditor.service.AssistantAdmissionLimiter;
import self.research.ontology.owlEditor.service.AssistantAuditService;
import self.research.ontology.owlEditor.service.AssistantSessionService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantSessionControllerTest {

    @Mock
    private AssistantSessionRepository sessionRepository;

    @Mock
    private ProjectMetadataService metadataService;

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private AssistantAuditService auditService;

    private AssistantSessionController controller;
    private final AtomicInteger ids = new AtomicInteger();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        AssistantSessionService service = new AssistantSessionService(
                sessionRepository, metadataService, mongoTemplate, auditService);
        ReflectionTestUtils.setField(service, "defaultRetrievalAttempts", 5);
        ReflectionTestUtils.setField(service, "defaultTokenBudget", 8000);
        ReflectionTestUtils.setField(service, "deadlineSeconds", 300L);
        ReflectionTestUtils.setField(service, "maxActiveSessionsPerUser", 20);
        controller = new AssistantSessionController(service, new AssistantAdmissionLimiter(4, 8, 32, 30, 1));
        when(metadataService.getMutationVersion("proj-1")).thenReturn(42L);
        when(mongoTemplate.count(any(Query.class), eq(AssistantSessionDocument.class))).thenReturn(0L);
        when(sessionRepository.save(any())).thenAnswer(inv -> {
            AssistantSessionDocument doc = inv.getArgument(0);
            doc.setId("session-" + ids.incrementAndGet());
            return doc;
        });
    }

    @Test
    void createSessionReturnsUnauthorizedWithoutBearerToken() {
        ResponseEntity<?> response = controller.createSession(request("proj-1"), new MockHttpServletRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("UNAUTHORIZED", body(response).get("errorCode"));
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void createSessionReturnsBadRequestWithoutProjectId() {
        ResponseEntity<?> response = controller.createSession(request(null), requestWithBearerToken());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_FAILED", body(response).get("errorCode"));
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void createSessionReturnsSnapshotAndBudgetOnSuccess() {
        AssistantSessionCreateRequest request = request("proj-1");
        request.setDocumentPath("/doc.owl");
        request.setActionType("ask");
        request.setActionContext("why is this inconsistent?");

        ResponseEntity<?> response = controller.createSession(request, requestWithBearerToken());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        AssistantSessionResponse body = assertInstanceOf(AssistantSessionResponse.class, response.getBody());
        assertEquals("session-1", body.getSessionId());
        assertEquals(42L, body.getSnapshot().getRevision());
        assertEquals("proj-1", body.getSnapshot().getProjectId());
        assertEquals("/doc.owl", body.getSnapshot().getDocumentPath());
        assertEquals(5, body.getBudget().getRetrievalCallsRemaining());
        assertEquals(5, body.getBudget().getMaxRetrievalCalls());
        assertTrue(body.getExpiresAt().isAfter(Instant.now()));
    }

    @Test
    void providerAndModelAreStoredTrimmedAndAudited() {
        AssistantSessionCreateRequest request = request("proj-1");
        request.setProvider(" claude ");
        request.setModel("claude-sonnet-4-5 ");

        ResponseEntity<?> response = controller.createSession(request, requestWithBearerToken());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<AssistantSessionDocument> saved = ArgumentCaptor.forClass(AssistantSessionDocument.class);
        verify(sessionRepository).save(saved.capture());
        assertEquals("claude", saved.getValue().getProvider());
        assertEquals("claude-sonnet-4-5", saved.getValue().getModel());
        ArgumentCaptor<AssistantAuditService.AssistantAuditEvent> event =
                ArgumentCaptor.forClass(AssistantAuditService.AssistantAuditEvent.class);
        verify(auditService).record(event.capture());
        assertEquals("ok", event.getValue().outcome());
        assertEquals("claude", event.getValue().provider());
        assertEquals("claude-sonnet-4-5", event.getValue().model());
        assertEquals("session-1", event.getValue().sessionId());
    }

    @Test
    void blankProviderIsTreatedAsAbsent() {
        AssistantSessionCreateRequest request = request("proj-1");
        request.setProvider("   ");

        assertEquals(HttpStatus.OK, controller.createSession(request, requestWithBearerToken()).getStatusCode());

        ArgumentCaptor<AssistantSessionDocument> saved = ArgumentCaptor.forClass(AssistantSessionDocument.class);
        verify(sessionRepository).save(saved.capture());
        assertEquals(null, saved.getValue().getProvider());
    }

    @Test
    void overlongModelIsRejectedWithoutCreating() {
        AssistantSessionCreateRequest request = request("proj-1");
        request.setModel("m".repeat(129));

        ResponseEntity<?> response = controller.createSession(request, requestWithBearerToken());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_FAILED", body(response).get("errorCode"));
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void tooManyActiveSessionsReturns429WithRetryAfterAndCreatesNothing() {
        when(mongoTemplate.count(any(Query.class), eq(AssistantSessionDocument.class))).thenReturn(20L);
        when(mongoTemplate.findOne(any(Query.class), eq(AssistantSessionDocument.class)))
                .thenReturn(AssistantSessionDocument.builder().expiresAt(Instant.now().plusSeconds(37)).build());

        ResponseEntity<?> response = controller.createSession(request("proj-1"), requestWithBearerToken());

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        int retryAfter = Integer.parseInt(response.getHeaders().getFirst("Retry-After"));
        assertTrue(retryAfter >= 36 && retryAfter <= 38, String.valueOf(retryAfter));
        Map<String, Object> body = body(response);
        assertEquals(false, body.get("ok"));
        assertEquals("RATE_LIMITED", body.get("errorCode"));
        assertEquals(retryAfter, body.get("retryAfterSeconds"));
        verify(sessionRepository, never()).save(any());
        ArgumentCaptor<AssistantAuditService.AssistantAuditEvent> event =
                ArgumentCaptor.forClass(AssistantAuditService.AssistantAuditEvent.class);
        verify(auditService).record(event.capture());
        assertEquals("rejected", event.getValue().outcome());
        assertEquals("RATE_LIMITED", event.getValue().errorCode());
        assertEquals("proj-1", event.getValue().projectId());
    }

    @Test
    void thirtyCreatesPerMinuteAreAllowedAndTheThirtyFirstIsRejected() {
        for (int i = 0; i < 30; i++) {
            assertEquals(HttpStatus.OK, controller.createSession(request("proj-1"), requestWithBearerToken())
                    .getStatusCode(), "create " + i);
        }

        ResponseEntity<?> rejected = controller.createSession(request("proj-1"), requestWithBearerToken());

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getStatusCode());
        int retryAfter = Integer.parseInt(rejected.getHeaders().getFirst("Retry-After"));
        assertTrue(retryAfter >= 1 && retryAfter <= 60, String.valueOf(retryAfter));
        assertEquals("RATE_LIMITED", body(rejected).get("errorCode"));
        verify(sessionRepository, times(30)).save(any());
        verify(mongoTemplate, times(30)).count(any(Query.class), eq(AssistantSessionDocument.class));
    }

    @Test
    void perMinuteLimitIsTrackedPerUser() {
        for (int i = 0; i < 30; i++) {
            controller.createSession(request("proj-1"), requestWithBearerToken());
        }

        ResponseEntity<?> other = controller.createSession(request("proj-1"), requestWithBearerToken("other@example.com"));

        assertEquals(HttpStatus.OK, other.getStatusCode());
    }

    @Test
    void activeLimitQueryOnlyCountsLiveSessionsOfTheCaller() {
        controller.createSession(request("proj-1"), requestWithBearerToken());

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate, atLeastOnce()).count(captor.capture(), eq(AssistantSessionDocument.class));
        List<Query> queries = captor.getAllValues();
        org.bson.Document filter = queries.get(0).getQueryObject();
        assertEquals("user@example.com", filter.get("userEmail"));
        assertEquals(AssistantSessionDocument.AssistantSessionStatus.ACTIVE, filter.get("status"));
        assertTrue(((org.bson.Document) filter.get("expiresAt")).containsKey("$gt"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    private static AssistantSessionCreateRequest request(String projectId) {
        AssistantSessionCreateRequest request = new AssistantSessionCreateRequest();
        request.setProjectId(projectId);
        return request;
    }

    private MockHttpServletRequest requestWithBearerToken() {
        return requestWithBearerToken("user@example.com");
    }

    private MockHttpServletRequest requestWithBearerToken(String email) {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"email\":\"" + email + "\"}").getBytes());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer header." + payload + ".signature");
        return request;
    }
}
