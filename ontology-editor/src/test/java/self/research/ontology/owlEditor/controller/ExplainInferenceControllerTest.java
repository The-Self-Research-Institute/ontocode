package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.common.admission.RefusalReason;
import self.research.ontology.owlEditor.service.BusyException;
import self.research.ontology.owlEditor.service.ExplainInferenceLimiter;
import self.research.ontology.owlEditor.service.ExplainInferenceRequest;
import self.research.ontology.owlEditor.service.JobAccess;
import self.research.ontology.owlEditor.service.ReasonerWorkerClient;
import self.research.ontology.owlEditor.service.SparqlQueryContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExplainInferenceControllerTest {

    private static final String NS = "http://example.org/p#";

    private ExplainInferenceController controller;
    private ReasonerWorkerClient worker;
    private JobAccess jobAccess;
    private ExplainInferenceLimiter limiter;
    private final JobAccess.Caller ann = new JobAccess.Caller("user-ann", "ann@x.org", false);
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @BeforeEach
    void setUp() {
        controller = new ExplainInferenceController();
        worker = mock(ReasonerWorkerClient.class);
        jobAccess = mock(JobAccess.class);
        limiter = new ExplainInferenceLimiter(2);
        ReflectionTestUtils.setField(controller, "reasonerWorkerClient", worker);
        ReflectionTestUtils.setField(controller, "reasonerWorkerEnabled", true);
        ReflectionTestUtils.setField(controller, "jobAccess", jobAccess);
        ReflectionTestUtils.setField(controller, "limiter", limiter);
        when(jobAccess.caller(any())).thenReturn(Optional.of(ann));
        when(jobAccess.hasProject(any(), any())).thenReturn(true);
        when(jobAccess.check(any(JobAccess.Caller.class), any(), any(), any()))
                .thenReturn(new JobAccess.Verdict(200, "user-ann"));
    }

    @AfterEach
    void clear() {
        SparqlQueryContext.clear();
    }

    private ExplainInferenceRequest personFact() {
        return new ExplainInferenceRequest("ClassAssertion", NS + "Customer2", "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
                NS + "Person", null, null, null, null, null);
    }

    private void workerAccepts(String jobId) {
        when(worker.submitExplainInference(any(), any(), any(), any()))
                .thenReturn(Map.of("success", true, "jobId", jobId, "status", "QUEUED", "queuePosition", 1,
                        "estimatedWaitTimeMs", 0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitIsAcceptedWithTheJobIdAndRegistersTheOwner() {
        workerAccepts("j1");

        ResponseEntity<Map<String, Object>> response = controller.submit("p1", personFact(), request);

        assertEquals(202, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("success"));
        assertEquals(true, response.getBody().get("async"));
        assertEquals("j1", response.getBody().get("jobId"));
        assertEquals("QUEUED", response.getBody().get("status"));
        verify(jobAccess).register(request, "j1", "p1");
        assertEquals(1, limiter.active("user-ann"));
        verify(worker).submitExplainInference(eq("p1"), eq(personFact()), eq("HERMIT"), isNull());
    }

    @Test
    void draftSubmitSendsTheTokenUserAsTheDraftOwner() {
        workerAccepts("j1");
        SparqlQueryContext.setWantsDraft(true);
        SparqlQueryContext.setUserId("user-ann");

        controller.submit("p1", personFact(), request);

        verify(worker).submitExplainInference(eq("p1"), any(), eq("HERMIT"), eq("user-ann"));
    }

    @Test
    void elkIsPassedOnlyWhenAskedFor() {
        workerAccepts("j1");
        ExplainInferenceRequest elk = new ExplainInferenceRequest("ClassAssertion", NS + "a", null, NS + "B", null, null,
                null, null, "elk");

        controller.submit("p1", elk, request);

        verify(worker).submitExplainInference(eq("p1"), any(), eq("ELK"), any());
    }

    @Test
    void unsupportedKindIsRefusedBeforeAnythingIsQueued() {
        ExplainInferenceRequest disjoint = new ExplainInferenceRequest("DisjointClasses", NS + "A", null, NS + "B", null, null,
                null, null, null);

        ResponseEntity<Map<String, Object>> response = controller.submit("p1", disjoint, request);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("UNSUPPORTED", response.getBody().get("explainStatus"));
        assertEquals(ExplainInferenceController.SUPPORTED_AXIOM_TYPES, response.getBody().get("supportedAxiomTypes"));
        assertTrue(response.getBody().get("error").toString().contains("DisjointClasses"));
        verify(worker, never()).submitExplainInference(any(), any(), any(), any());
    }

    @Test
    void missingPiecesAreRefusedWithAPlainMessage() {
        ExplainInferenceRequest noClass = new ExplainInferenceRequest("ClassAssertion", NS + "a", null, null, null, null,
                null, null, null);
        ExplainInferenceRequest noValue = new ExplainInferenceRequest("DataPropertyAssertion", NS + "a", NS + "age", null, null,
                null, null, null, null);
        ExplainInferenceRequest shortUnion = new ExplainInferenceRequest("ClassAssertion", NS + "a", null, null, null, null,
                List.of(NS + "A"), "UNION", null);
        ExplainInferenceRequest noOperator = new ExplainInferenceRequest("ClassAssertion", NS + "a", null, null, null, null,
                List.of(NS + "A", NS + "B"), null, null);

        for (ExplainInferenceRequest bad : List.of(noClass, noValue, shortUnion, noOperator)) {
            ResponseEntity<Map<String, Object>> response = controller.submit("p1", bad, request);
            assertEquals(400, response.getStatusCode().value());
            assertFalse(response.getBody().containsKey("explainStatus"));
            assertEquals(false, response.getBody().get("success"));
        }
        verify(worker, never()).submitExplainInference(any(), any(), any(), any());
    }

    @Test
    void unionFactIsAccepted() {
        workerAccepts("j1");
        ExplainInferenceRequest union = new ExplainInferenceRequest("ClassAssertion", NS + "t1", null, null, null, null,
                List.of(NS + "MeatTopping", NS + "SeafoodTopping"), "UNION", null);

        ResponseEntity<Map<String, Object>> response = controller.submit("p1", union, request);

        assertEquals(202, response.getStatusCode().value());
    }

    @Test
    void workerQueueFullBecomesTheStandardBusyAnswer() {
        when(worker.submitExplainInference(any(), any(), any(), any()))
                .thenReturn(Map.of("success", false, "rateLimited", true, "error", "5 reasoning jobs are already queued."));

        BusyException busy = assertThrows(BusyException.class, () -> controller.submit("p1", personFact(), request));

        assertEquals(RefusalReason.QUEUE_FULL, busy.reason());
        assertEquals("5 reasoning jobs are already queued.", busy.getMessage());
        assertEquals(10, busy.retryAfterSeconds());
        assertEquals(0, limiter.active("user-ann"));
    }

    @Test
    void aThirdExplanationFromTheSameUserIsRefusedAsBusy() {
        workerAccepts("j1");
        controller.submit("p1", personFact(), request);
        workerAccepts("j2");
        controller.submit("p1", personFact(), request);

        BusyException busy = assertThrows(BusyException.class, () -> controller.submit("p1", personFact(), request));

        assertEquals(RefusalReason.OWNER_LIMIT, busy.reason());
        verify(worker, org.mockito.Mockito.times(2)).submitExplainInference(any(), any(), any(), any());
    }

    @Test
    void anotherUsersLimitIsNotUsedUp() {
        workerAccepts("j1");
        controller.submit("p1", personFact(), request);
        workerAccepts("j2");
        controller.submit("p1", personFact(), request);
        when(jobAccess.caller(any())).thenReturn(Optional.of(new JobAccess.Caller("user-bob", "bob@x.org", false)));
        workerAccepts("j3");

        ResponseEntity<Map<String, Object>> response = controller.submit("p1", personFact(), request);

        assertEquals(202, response.getStatusCode().value());
    }

    @Test
    void workerDownIsAServerErrorWithAFriendlyMessage() {
        when(worker.submitExplainInference(any(), any(), any(), any()))
                .thenReturn(Map.of("success", false, "error", "Connection refused"));

        ResponseEntity<Map<String, Object>> response = controller.submit("p1", personFact(), request);

        assertEquals(500, response.getStatusCode().value());
        assertEquals(false, response.getBody().get("success"));
    }

    @Test
    void withoutTheReasoningServiceTheAnswerIsUnavailable() {
        ReflectionTestUtils.setField(controller, "reasonerWorkerEnabled", false);

        ResponseEntity<Map<String, Object>> response = controller.submit("p1", personFact(), request);

        assertEquals(503, response.getStatusCode().value());
        assertEquals("UNAVAILABLE", response.getBody().get("explainStatus"));
    }

    @Test
    void anonymousCallerIsNotServed() {
        when(jobAccess.caller(any())).thenReturn(Optional.empty());

        assertEquals(401, controller.submit("p1", personFact(), request).getStatusCode().value());
        assertEquals(401, controller.poll("p1", "j1", request).getStatusCode().value());
        assertEquals(401, controller.cancel("p1", "j1", request).getStatusCode().value());
    }

    @Test
    void callerWithoutTheProjectGetsNotFound() {
        when(jobAccess.hasProject(any(), eq("p1"))).thenReturn(false);

        assertEquals(404, controller.submit("p1", personFact(), request).getStatusCode().value());
        verify(worker, never()).submitExplainInference(any(), any(), any(), any());
    }

    @Test
    void pollReturnsTheWorkerResultFlattenedAndReleasesTheUsersSlot() {
        workerAccepts("j1");
        controller.submit("p1", personFact(), request);
        when(jobAccess.isRegistered("j1")).thenReturn(true);
        when(worker.getJob("j1")).thenReturn(Map.of("jobId", "j1", "jobType", "EXPLAIN_INFERENCE", "projectId", "p1",
                "status", "COMPLETED", "explainStatus", "EXPLAINED", "axiomCount", 2));

        ResponseEntity<Map<String, Object>> response = controller.poll("p1", "j1", request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("COMPLETED", response.getBody().get("status"));
        assertEquals("EXPLAINED", response.getBody().get("explainStatus"));
        assertEquals(2, response.getBody().get("axiomCount"));
        assertEquals(0, limiter.active("user-ann"));
    }

    @Test
    void pollWhileRunningKeepsTheSlotHeld() {
        workerAccepts("j1");
        controller.submit("p1", personFact(), request);
        when(worker.getJob("j1")).thenReturn(Map.of("jobId", "j1", "jobType", "EXPLAIN_INFERENCE", "projectId", "p1",
                "status", "PROCESSING"));

        ResponseEntity<Map<String, Object>> response = controller.poll("p1", "j1", request);

        assertEquals("PROCESSING", response.getBody().get("status"));
        assertEquals(1, limiter.active("user-ann"));
    }

    @Test
    void someoneElsesJobLooksLikeItDoesNotExist() {
        when(jobAccess.isRegistered("j1")).thenReturn(true);
        when(jobAccess.check(any(JobAccess.Caller.class), eq("p1"), eq("j1"), any()))
                .thenReturn(new JobAccess.Verdict(404, "user-ann"));

        ResponseEntity<Map<String, Object>> response = controller.poll("p1", "j1", request);

        assertEquals(404, response.getStatusCode().value());
        verify(worker, never()).getJob(any());
        assertEquals(404, controller.cancel("p1", "j1", request).getStatusCode().value());
        verify(worker, never()).cancelJob(any());
    }

    @Test
    void aJobOfAnotherKindIsNotServedThroughThisRoute() {
        when(worker.getJob("j9")).thenReturn(Map.of("jobId", "j9", "jobType", "DL_QUERY", "projectId", "p1",
                "status", "COMPLETED"));

        assertEquals(404, controller.poll("p1", "j9", request).getStatusCode().value());
    }

    @Test
    void workerThatLostTheJobGivesAFailedStatusWithAClearMessage() {
        when(jobAccess.isRegistered("j1")).thenReturn(true);
        when(worker.getJob("j1")).thenReturn(Map.of("success", false, "error", "404 Not Found"));

        ResponseEntity<Map<String, Object>> response = controller.poll("p1", "j1", request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("FAILED", response.getBody().get("status"));
        assertEquals(ExplainInferenceController.WORKER_LOST_MESSAGE, response.getBody().get("error"));
    }

    @Test
    void unknownJobIsNotFound() {
        when(worker.getJob("nope")).thenReturn(Map.of("success", false, "error", "404 Not Found"));

        assertEquals(404, controller.poll("p1", "nope", request).getStatusCode().value());
    }

    @Test
    void cancelAsksTheWorkerToStopAndFreesTheSlot() {
        workerAccepts("j1");
        controller.submit("p1", personFact(), request);
        when(worker.cancelJob("j1")).thenReturn(Map.of("success", true, "cancelled", true));

        ResponseEntity<Map<String, Object>> response = controller.cancel("p1", "j1", request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("cancelled"));
        assertEquals(0, limiter.active("user-ann"));
    }
}
