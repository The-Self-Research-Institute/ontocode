package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import self.research.ontology.owlEditor.document.AssistantSessionDocument;
import self.research.ontology.owlEditor.document.AssistantSessionDocument.AssistantSessionStatus;
import self.research.ontology.owlEditor.service.AssistantReasonerToolService.ReasonerToolResult;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantReasonerToolServiceTest {

    @Mock
    private AssistantSessionService sessionService;

    @Mock
    private RestTemplate restTemplate;

    private AssistantReasonerToolService toolService;
    private AssistantAdmissionLimiter limiter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        limiter = new AssistantAdmissionLimiter(4, 2, 32, 30, 3, Clock.systemUTC());
        toolService = new AssistantReasonerToolService(sessionService, limiter);
        ReflectionTestUtils.setField(toolService, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(toolService, "pluginServiceUrl", "http://localhost:8087");
        ReflectionTestUtils.setField(toolService, "maxUnsatisfiableClasses", 50);
        ReflectionTestUtils.setField(toolService, "maxExplanationBytes", 50_000);
        when(sessionService.tryConsumeTokenBudget(anyString(), anyInt())).thenReturn(true);
        when(sessionService.isRevisionStale(any())).thenReturn(false);
    }

    @SuppressWarnings("unchecked")
    private void mockExchange(ResponseEntity<Map> response) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(response);
    }

    @Test
    void returnsSessionNotFoundWhenSessionMissing() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.empty());

        ReasonerToolResult result = toolService.checkConsistency("s1", "u@x.com", "Bearer t");

        assertFalse(result.isOk());
        assertEquals("SESSION_NOT_FOUND", result.getErrorCode());
    }

    @Test
    void returnsRevisionStaleAndNeverSpendsBudget() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.isRevisionStale(any())).thenReturn(true);

        ReasonerToolResult result = toolService.checkConsistency("s1", "u@x.com", "Bearer t");

        assertFalse(result.isOk());
        assertEquals("REVISION_STALE", result.getErrorCode());
        verify(sessionService, org.mockito.Mockito.never()).tryConsumeRetrievalAttempt(anyString());
    }

    @Test
    void projectCapacityRejectsWithRateLimited() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        AssistantAdmissionLimiter.ToolAdmission a = limiter.tryAcquireTool("other1@x.com", "proj-1");
        AssistantAdmissionLimiter.ToolAdmission b = limiter.tryAcquireTool("other2@x.com", "proj-1");

        ReasonerToolResult result = toolService.checkConsistency("s1", "u@x.com", "Bearer t");

        assertFalse(result.isOk());
        assertEquals("RATE_LIMITED", result.getErrorCode());
        ((AssistantAdmissionLimiter.Admitted) a).close();
        ((AssistantAdmissionLimiter.Admitted) b).close();
    }

    @Test
    void returnsBudgetExhaustedWhenRetrievalAttemptFails() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(false);

        ReasonerToolResult result = toolService.checkConsistency("s1", "u@x.com", "Bearer t");

        assertFalse(result.isOk());
        assertEquals("BUDGET_EXHAUSTED", result.getErrorCode());
    }

    @Test
    void checkConsistencyHappyPathForwardsAuthorizationAndReturnsData() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        Map<String, Object> body = new HashMap<>();
        body.put("consistent", true);
        body.put("reasonerType", "HermiT");
        body.put("durationMs", 42);
        body.put("projectId", "proj-1");
        mockExchange(new ResponseEntity<>(body, HttpStatus.OK));

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);

        ReasonerToolResult result = toolService.checkConsistency("s1", "u@x.com", "Bearer abc123");

        assertTrue(result.isOk());
        assertEquals(Boolean.TRUE, result.getData().get("consistent"));
        assertFalse(result.isTruncated());
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.POST), entityCaptor.capture(), eq(Map.class));
        assertEquals("http://localhost:8087/api/reasoner/proj-1/consistency", urlCaptor.getValue());
        HttpHeaders sentHeaders = entityCaptor.getValue().getHeaders();
        assertEquals("Bearer abc123", sentHeaders.getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void explainInconsistencyHappyPathHitsExplainEndpointWithCappedJustifications() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("usedReasoner", "HermiT");
        body.put("isConsistent", false);
        body.put("causes", List.of(Map.of("type", "JUSTIFICATIONS", "title", "Minimal Inconsistency Justifications")));
        mockExchange(new ResponseEntity<>(body, HttpStatus.OK));

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);

        ReasonerToolResult result = toolService.explainInconsistency("s1", "u@x.com", "Bearer abc123");

        assertTrue(result.isOk());
        assertEquals(Boolean.FALSE, result.getData().get("isConsistent"));
        verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.POST), entityCaptor.capture(), eq(Map.class));
        assertEquals("http://localhost:8087/api/reasoner/proj-1/explain-inconsistency", urlCaptor.getValue());
        @SuppressWarnings("unchecked")
        Map<String, String> sentBody = (Map<String, String>) entityCaptor.getValue().getBody();
        assertEquals("3", sentBody.get("maxJustifications"));
    }

    @Test
    void timeoutProducesReasonerUnavailableAndStillConsumesRetrievalAttemptButNotTokenBudget() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        ReasonerToolResult result = toolService.checkConsistency("s1", "u@x.com", "Bearer t");

        assertFalse(result.isOk());
        assertEquals("REASONER_UNAVAILABLE", result.getErrorCode());
        verify(sessionService).tryConsumeRetrievalAttempt("s1");
        verify(sessionService, org.mockito.Mockito.never()).tryConsumeTokenBudget(anyString(), anyInt());
    }

    @Test
    void pluginServiceFailureBodyIsPassedThroughWithSuggestion() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("error", "Unknown reasoner type in request: FOO");
        body.put("errorType", "INVALID_REASONER_TYPE");
        body.put("suggestion", "Select a supported reasoner and try again.");
        mockExchange(new ResponseEntity<>(body, HttpStatus.BAD_REQUEST));

        ReasonerToolResult result = toolService.checkConsistency("s1", "u@x.com", "Bearer t");

        assertFalse(result.isOk());
        assertEquals("INVALID_REASONER_TYPE", result.getErrorCode());
        assertTrue(result.getMessage().contains("Select a supported reasoner"));
    }

    @Test
    void asyncWorkerModeAcceptedResponseProducesReasonerUnavailableInsteadOfMisreadingIt() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        Map<String, Object> body = new HashMap<>();
        body.put("async", true);
        body.put("taskId", "job-1");
        body.put("pollUrl", "/api/dl-query/jobs/job-1");
        mockExchange(new ResponseEntity<>(body, HttpStatus.ACCEPTED));

        ReasonerToolResult result = toolService.checkConsistency("s1", "u@x.com", "Bearer t");

        assertFalse(result.isOk());
        assertEquals("REASONER_UNAVAILABLE", result.getErrorCode());
    }

    private void acceptedJob(ReasonerWorkerClient worker, Map<String, Object>... polls) {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        ReflectionTestUtils.setField(toolService, "reasonerWorkerClient", worker);
        ReflectionTestUtils.setField(toolService, "asyncWaitMs", 500L);
        ReflectionTestUtils.setField(toolService, "asyncPollMs", 5L);
        Map<String, Object> body = new HashMap<>();
        body.put("async", true);
        body.put("jobId", "job-1");
        mockExchange(new ResponseEntity<>(body, HttpStatus.ACCEPTED));
        org.mockito.stubbing.OngoingStubbing<Map<String, Object>> stub = when(worker.getJob("job-1"));
        for (Map<String, Object> poll : polls) {
            stub = stub.thenReturn(poll);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBackgroundReasonerJobIsWaitedForAndItsAnswerIsReturned() {
        ReasonerWorkerClient worker = org.mockito.Mockito.mock(ReasonerWorkerClient.class);
        acceptedJob(worker, Map.of("status", "RUNNING"),
                Map.of("status", "COMPLETED", "consistent", true));

        ReasonerToolResult result = toolService.checkConsistency("s1", "u@x.com", "Bearer t");

        assertTrue(result.isOk());
        assertEquals(Boolean.TRUE, ((Map<String, Object>) result.getData()).get("consistent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedBackgroundJobReportsTheReasonersOwnError() {
        ReasonerWorkerClient worker = org.mockito.Mockito.mock(ReasonerWorkerClient.class);
        acceptedJob(worker, Map.of("status", "FAILED", "error", "Ontology too large"));

        ReasonerToolResult result = toolService.checkConsistency("s1", "u@x.com", "Bearer t");

        assertFalse(result.isOk());
        assertEquals("REASONER_ERROR", result.getErrorCode());
        assertTrue(result.getMessage().contains("Ontology too large"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBackgroundJobThatOutlastsTheWaitAsksTheUserToRetry() {
        ReasonerWorkerClient worker = org.mockito.Mockito.mock(ReasonerWorkerClient.class);
        acceptedJob(worker, Map.of("status", "RUNNING"));

        ReasonerToolResult result = toolService.checkConsistency("s1", "u@x.com", "Bearer t");

        assertFalse(result.isOk());
        assertEquals("REASONER_UNAVAILABLE", result.getErrorCode());
        assertTrue(result.getMessage().contains("still working"));
    }

    @Test
    void oversizedUnsatisfiableClassesListIsTruncatedWithTotalCount() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        ReflectionTestUtils.setField(toolService, "maxUnsatisfiableClasses", 2);
        Map<String, Object> body = new HashMap<>();
        body.put("consistent", false);
        List<Object> unsatisfiable = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            unsatisfiable.add(Map.of("iri", "http://example.org/C" + i, "label", "C" + i));
        }
        body.put("unsatisfiableClasses", unsatisfiable);
        mockExchange(new ResponseEntity<>(body, HttpStatus.OK));

        ReasonerToolResult result = toolService.checkConsistency("s1", "u@x.com", "Bearer t");

        assertTrue(result.isOk());
        assertTrue(result.isTruncated());
        assertEquals(2, ((List<?>) result.getData().get("unsatisfiableClasses")).size());
        assertEquals(5, result.getData().get("unsatisfiableClassesTotalCount"));
    }

    @Test
    void oversizedExplanationIsTruncatedWithTotalCount() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        ReflectionTestUtils.setField(toolService, "maxExplanationBytes", 200);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("isConsistent", false);
        List<Object> causes = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            causes.add(Map.of("type", "JUSTIFICATIONS", "title", "Explanation " + i,
                    "description", "A fairly long description of justification number " + i + " padded out with text."));
        }
        body.put("causes", causes);
        mockExchange(new ResponseEntity<>(body, HttpStatus.OK));

        ReasonerToolResult result = toolService.explainInconsistency("s1", "u@x.com", "Bearer t");

        assertTrue(result.isOk());
        assertTrue(result.isTruncated());
        assertTrue(((List<?>) result.getData().get("causes")).size() < 20);
        assertEquals(20, result.getData().get("causesTotalCount"));
    }

    @Test
    void alreadyConsistentExplainResponsePassesThroughAsNormalSuccess() {
        when(sessionService.getActiveSession("s1", "u@x.com")).thenReturn(Optional.of(activeSession()));
        when(sessionService.tryConsumeRetrievalAttempt("s1")).thenReturn(true);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("isConsistent", true);
        body.put("message", "The ontology is consistent — no explanation needed.");
        mockExchange(new ResponseEntity<>(body, HttpStatus.OK));

        ReasonerToolResult result = toolService.explainInconsistency("s1", "u@x.com", "Bearer t");

        assertTrue(result.isOk());
        assertEquals(Boolean.TRUE, result.getData().get("isConsistent"));
    }

    private AssistantSessionDocument activeSession() {
        return AssistantSessionDocument.builder()
                .id("s1")
                .projectId("proj-1")
                .pinnedRevision(42L)
                .status(AssistantSessionStatus.ACTIVE)
                .build();
    }
}
