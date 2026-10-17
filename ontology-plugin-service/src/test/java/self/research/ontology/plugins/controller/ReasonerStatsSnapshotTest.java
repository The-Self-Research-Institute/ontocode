package self.research.ontology.plugins.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import self.research.ontology.plugins.service.ReasonerService;
import self.research.ontology.plugins.service.ReasonerType;
import self.research.ontology.plugins.service.ReasonerWorkerClient;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReasonerStatsSnapshotTest {

    private ReasonerController controller;
    private ReasonerService reasonerService;
    private ReasonerWorkerClient workerClient;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        controller = new ReasonerController();
        reasonerService = mock(ReasonerService.class);
        workerClient = mock(ReasonerWorkerClient.class);
        restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(controller, "reasonerService", reasonerService);
        ReflectionTestUtils.setField(controller, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(controller, "editorServiceUrl", "http://owl-editor:8083");
        ReflectionTestUtils.setField(controller, "internalToken", "ontocode-internal");
        ReflectionTestUtils.setField(controller, "reasonerWorkerClient", workerClient);
        ReflectionTestUtils.setField(controller, "reasonerWorkerEnabled", true);
        ReflectionTestUtils.setField(controller, "statsSnapshotPollMs", 1L);
        ReflectionTestUtils.setField(controller, "maxDraftOntologies", 10);
    }

    private static Map<String, Object> snapshotStats() {
        return Map.of(
                "classHierarchyNodes", 12,
                "objectPropertyNodes", 3,
                "dataPropertyNodes", 2,
                "individuals", 5,
                "satisfiableClasses", 11,
                "unsatisfiableClasses", 1,
                "isConsistent", true);
    }

    @Test
    void publishedStatsComeFromAnAlreadyCachedSnapshot() {
        Map<String, Object> submitted = new HashMap<>();
        submitted.put("success", true);
        submitted.put("jobId", "job-1");
        submitted.put("jobType", "REASONER_SNAPSHOT");
        submitted.put("status", "COMPLETED");
        submitted.put("consistent", true);
        submitted.put("totalClasses", 12);
        submitted.put("reasonerType", "HermiT");
        submitted.put("stats", snapshotStats());
        when(workerClient.submit("REASONER_SNAPSHOT", "proj-1", "HERMIT")).thenReturn(submitted);

        ResponseEntity<Map<String, Object>> response = controller.getReasonerStats("proj-1", "hermit", null);

        Map<String, Object> body = response.getBody();
        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, body.get("success"));
        assertEquals("proj-1", body.get("projectId"));
        assertEquals("HermiT", body.get("reasonerType"));
        assertEquals(true, body.get("isConsistent"));
        assertEquals(12, body.get("classCount"));
        assertEquals(3, body.get("objectPropertyCount"));
        assertEquals(2, body.get("dataPropertyCount"));
        assertEquals(5, body.get("propertyCount"));
        assertEquals(5, body.get("individualCount"));
        assertEquals(1, body.get("unsatisfiableClasses"));
        assertEquals(11, body.get("satisfiableClasses"));
        assertEquals(true, body.get("supportsOWL2DL"));
        verifyNoInteractions(reasonerService);
        verifyNoInteractions(restTemplate);
    }

    @Test
    void queuedSnapshotIsWaitedForWithinTheBudget() {
        when(workerClient.submit("REASONER_SNAPSHOT", "proj-1", "ELK"))
                .thenReturn(Map.of("success", true, "jobId", "job-2", "status", "QUEUED"));
        when(workerClient.getJob("job-2"))
                .thenReturn(Map.of("jobId", "job-2", "status", "PROCESSING"))
                .thenReturn(Map.of("jobId", "job-2", "status", "COMPLETED", "result", Map.of(
                        "success", true, "consistent", true, "totalClasses", 12,
                        "reasonerType", "ELK", "stats", snapshotStats())));

        Map<String, Object> body = controller.getReasonerStats("proj-1", "ELK", null).getBody();

        assertEquals(true, body.get("success"));
        assertEquals("ELK", body.get("reasonerType"));
        assertEquals(12, body.get("classCount"));
        verifyNoInteractions(reasonerService);
    }

    @Test
    void snapshotThatNeverFinishesIsCancelledAndReportedAsATimeout() {
        ReflectionTestUtils.setField(controller, "statsSnapshotWaitMs", 20L);
        when(workerClient.submit(anyString(), anyString(), anyString()))
                .thenReturn(Map.of("success", true, "jobId", "job-3", "status", "QUEUED"));
        when(workerClient.getJob("job-3")).thenReturn(Map.of("jobId", "job-3", "status", "PROCESSING"));

        ResponseEntity<Map<String, Object>> response = controller.getReasonerStats("proj-1", "HERMIT", null);

        assertEquals(500, response.getStatusCode().value());
        assertEquals(false, response.getBody().get("success"));
        verify(workerClient).cancelJob("job-3");
        verifyNoInteractions(reasonerService);
    }

    @Test
    void fullWorkerQueueAnswersTooManyRequests() {
        when(workerClient.submit(anyString(), anyString(), anyString()))
                .thenReturn(Map.of("success", false, "rateLimited", true, "error", "Queue is full"));

        ResponseEntity<Map<String, Object>> response = controller.getReasonerStats("proj-1", "HERMIT", null);

        assertEquals(429, response.getStatusCode().value());
        verifyNoInteractions(reasonerService);
    }

    @Test
    void draftStatsUseTheUsersDraftInsteadOfThePublishedOntology() {
        byte[] draft = "<urn:ex/A> <urn:ex/type> <urn:ex/Class> .".getBytes(StandardCharsets.UTF_8);
        when(restTemplate.exchange(contains("/internal/reasoning/proj-1/export.ttl"), eq(HttpMethod.GET), any(),
                eq(byte[].class))).thenReturn(ResponseEntity.ok(draft));
        when(reasonerService.getReasonerStats(any(), eq(ReasonerType.HERMIT))).thenReturn(new HashMap<>(Map.of("classCount", 1)));

        Map<String, Object> body = controller.getReasonerStats("proj-1", "HERMIT", "user-1").getBody();

        assertEquals(true, body.get("success"));
        assertEquals(1, body.get("classCount"));
        verify(workerClient, never()).submit(anyString(), anyString(), anyString());
        verify(restTemplate).exchange(contains("userId=user-1&draft=true"), eq(HttpMethod.GET), any(), eq(byte[].class));
        verify(restTemplate, never()).exchange(contains("/api/ontology-file/"), any(), any(), eq(byte[].class));
    }

    @Test
    void inconsistentSnapshotFallsBackToTheLocalStats() throws Exception {
        when(workerClient.submit(anyString(), anyString(), anyString()))
                .thenReturn(Map.of("success", true, "jobId", "job-4", "status", "COMPLETED", "consistent", false));
        @SuppressWarnings("unchecked")
        Map<String, org.semanticweb.owlapi.model.OWLOntology> cache =
                (Map<String, org.semanticweb.owlapi.model.OWLOntology>) ReflectionTestUtils.getField(controller, "ontologyCache");
        cache.put("proj-1", org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager().createOntology());
        when(reasonerService.getReasonerStats(any(), eq(ReasonerType.HERMIT)))
                .thenReturn(new HashMap<>(Map.of("isConsistent", false, "unsatisfiableClasses", -1)));

        Map<String, Object> body = controller.getReasonerStats("proj-1", "HERMIT", null).getBody();

        assertEquals(false, body.get("isConsistent"));
        assertFalse(body.containsKey("classCount"));
        verify(reasonerService).getReasonerStats(any(), eq(ReasonerType.HERMIT));
    }
}
