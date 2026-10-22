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
import static org.mockito.Mockito.when;

class DLQueryJobControllerTest {

    private final Map<String, DLQueryJob> retained = new HashMap<>();
    private ReasonerWorkerClient workerClient;
    private ReasoningJobRelayService relay;
    private DLQueryJobController controller;

    @BeforeEach
    void setUp() {
        EditorJobResultRetention retention = mock(EditorJobResultRetention.class);
        when(retention.get(anyString())).thenAnswer(invocation -> Optional.ofNullable(retained.get(invocation.getArgument(0))));
        doAnswer(invocation -> {
            DLQueryJob job = invocation.getArgument(0);
            retained.put(job.getJobId(), job);
            return null;
        }).when(retention).retain(any());
        relay = new ReasoningJobRelayService(mock(SimpMessagingTemplate.class), retention);
        workerClient = mock(ReasonerWorkerClient.class);
        controller = new DLQueryJobController(mock(DLQueryQueueManager.class));
        ReflectionTestUtils.setField(controller, "reasonerWorkerClient", workerClient);
        ReflectionTestUtils.setField(controller, "reasoningJobRelayService", relay);
        ReflectionTestUtils.setField(controller, "reasonerWorkerEnabled", true);
        relay.rememberSubmittedJob("j1", "P1", "Person", "DL_QUERY", Map.of("status", "QUEUED", "queuePosition", 1));
    }

    @Test
    void jobTheWorkerNoLongerKnowsIsReportedAsFailed() {
        when(workerClient.getJob("j1")).thenReturn(Map.of("success", false, "error", "404 Not Found: [no body]"));

        ResponseEntity<Map<String, Object>> response = controller.getJob("j1");

        assertEquals("FAILED", response.getBody().get("status"));
        assertEquals(DLQueryJobController.WORKER_LOST_JOB_MESSAGE, response.getBody().get("error"));
        assertEquals(DLQueryJob.Status.FAILED, relay.getJob("j1").getStatus());
    }

    @Test
    void unreachableWorkerKeepsTheLastKnownState() {
        when(workerClient.getJob("j1")).thenReturn(Map.of("success", false,
                "error", "The reasoning service is temporarily unavailable. Please try again in a minute."));

        ResponseEntity<Map<String, Object>> response = controller.getJob("j1");

        assertEquals("QUEUED", response.getBody().get("status"));
        assertEquals(DLQueryJob.Status.QUEUED, relay.getJob("j1").getStatus());
    }

    @Test
    void liveWorkerAnswerIsPassedThrough() {
        Map<String, Object> remote = Map.of("jobId", "j1", "status", "PROCESSING");
        when(workerClient.getJob("j1")).thenReturn(remote);

        assertEquals(remote, controller.getJob("j1").getBody());
    }
}
