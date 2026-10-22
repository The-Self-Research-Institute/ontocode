package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.model.DLQueryJob;
import self.research.ontology.owlEditor.service.DLQueryQueueManager;
import self.research.ontology.owlEditor.service.EditorJobResultRetention;
import self.research.ontology.owlEditor.service.ReasonerWorkerClient;
import self.research.ontology.owlEditor.service.ReasoningJobRelayService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReasonerJobCancelTest {

    private final Map<String, DLQueryJob> retained = new HashMap<>();
    private ReasonerWorkerClient workerClient;
    private ReasoningJobRelayService relay;
    private DLQueryJobController dlQueryJobs;
    private ReasonerController reasoner;

    @BeforeEach
    void setUp() {
        EditorJobResultRetention retention = mock(EditorJobResultRetention.class);
        when(retention.get(anyString())).thenAnswer(inv -> Optional.ofNullable(retained.get(inv.getArgument(0))));
        doAnswer(inv -> {
            DLQueryJob job = inv.getArgument(0);
            retained.put(job.getJobId(), job);
            return null;
        }).when(retention).retain(any());
        relay = new ReasoningJobRelayService(mock(SimpMessagingTemplate.class), retention);
        workerClient = mock(ReasonerWorkerClient.class);

        dlQueryJobs = new DLQueryJobController(mock(DLQueryQueueManager.class));
        ReflectionTestUtils.setField(dlQueryJobs, "reasonerWorkerClient", workerClient);
        ReflectionTestUtils.setField(dlQueryJobs, "reasoningJobRelayService", relay);
        ReflectionTestUtils.setField(dlQueryJobs, "reasonerWorkerEnabled", true);

        reasoner = new ReasonerController();
        ReflectionTestUtils.setField(reasoner, "reasonerWorkerClient", workerClient);
        ReflectionTestUtils.setField(reasoner, "reasoningJobRelayService", relay);
        ReflectionTestUtils.setField(reasoner, "reasonerWorkerEnabled", true);

        relay.rememberSubmittedJob("j1", "P1", null, "REASONER_SNAPSHOT", Map.of("status", "PROCESSING"));
    }

    @Test
    void dlQueryCancelStopsTheRelayedJobSoPollsEnd() {
        when(workerClient.cancelJob("j1")).thenReturn(Map.of("success", true, "cancelled", true));

        ResponseEntity<Map<String, Object>> response = dlQueryJobs.cancelJob("j1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("cancelled"));
        Map<String, Object> poll = dlQueryJobs.getJob("j1").getBody();
        assertEquals("FAILED", poll.get("status"));
        assertEquals(DLQueryJobController.STOPPED_MESSAGE, poll.get("error"));
    }

    @Test
    void sharedJobThatTheWorkerKeepsRunningIsLeftAloneForOtherRequesters() {
        when(workerClient.cancelJob("j1")).thenReturn(Map.of("success", true, "cancelled", false));

        Map<String, Object> body = dlQueryJobs.cancelJob("j1").getBody();

        assertEquals(false, body.get("cancelled"));
        assertEquals(DLQueryJob.Status.PROCESSING, relay.getJob("j1").getStatus());
    }

    @Test
    void cancelWithoutAWorkerIsANoOp() {
        ReflectionTestUtils.setField(dlQueryJobs, "reasonerWorkerEnabled", false);
        ReflectionTestUtils.setField(reasoner, "reasonerWorkerEnabled", false);

        assertEquals(Map.of("success", true, "cancelled", false), dlQueryJobs.cancelJob("j1").getBody());
        assertEquals(Map.of("success", true, "cancelled", false), reasoner.cancelReasonerJob("P1", "j1").getBody());
        verify(workerClient, never()).cancelJob(anyString());
    }

    @Test
    void projectRouteCancelsTheJobAndStopsTheRelayedCopy() {
        when(workerClient.cancelJob("j1")).thenReturn(Map.of("success", true, "cancelled", true));

        ResponseEntity<Map<String, Object>> response = reasoner.cancelReasonerJob("P1", "j1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("cancelled"));
        assertEquals(DLQueryJob.Status.FAILED, relay.getJob("j1").getStatus());
        assertEquals(DLQueryJobController.STOPPED_MESSAGE, relay.getJob("j1").getError());
    }

    @Test
    void projectRouteRefusesAJobThatBelongsToAnotherProject() {
        ResponseEntity<Map<String, Object>> response = reasoner.cancelReasonerJob("OTHER", "j1");

        assertEquals(404, response.getStatusCode().value());
        verify(workerClient, never()).cancelJob(anyString());
        assertEquals(DLQueryJob.Status.PROCESSING, relay.getJob("j1").getStatus());
    }

    @Test
    void projectRouteCancelsJobsTheRelayDoesNotKnow() {
        when(workerClient.cancelJob("hier-1")).thenReturn(Map.of("success", true, "cancelled", true));

        Map<String, Object> body = reasoner.cancelReasonerJob("P1", "hier-1").getBody();

        assertEquals(true, body.get("cancelled"));
        verify(workerClient).cancelJob("hier-1");
    }
}
