package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import self.research.ontology.owlEditor.model.DLQueryJob;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReasoningJobRelayServiceTest {

    private final Map<String, DLQueryJob> retained = new HashMap<>();
    private SimpMessagingTemplate messaging;
    private ReasoningJobRelayService relay;

    @BeforeEach
    void setUp() {
        EditorJobResultRetention retention = mockRetention(retained);
        messaging = mock(SimpMessagingTemplate.class);
        relay = new ReasoningJobRelayService(messaging, retention);
    }

    @Test
    void lateProgressEventDoesNotReopenAFinishedJob() {
        relay.applyWorkerEvent(Map.of("jobId", "j1", "projectId", "P1", "status", "COMPLETED",
                "result", Map.of("success", true)));

        relay.applyWorkerEvent(Map.of("jobId", "j1", "projectId", "P1", "status", "PROCESSING"));

        assertEquals(DLQueryJob.Status.COMPLETED, relay.getJob("j1").getStatus());
    }

    @Test
    void submitResponseArrivingAfterAFastJobFinishedKeepsTheFinishedState() {
        relay.applyWorkerEvent(Map.of("jobId", "j1", "projectId", "P1", "status", "COMPLETED",
                "result", Map.of("success", true)));

        relay.rememberSubmittedJob("j1", "P1", "Person", "DL_QUERY", Map.of("status", "QUEUED", "queuePosition", 1));

        DLQueryJob job = relay.getJob("j1");
        assertEquals(DLQueryJob.Status.COMPLETED, job.getStatus());
        assertEquals("Person", job.getExpression());
        assertNotNull(job.getResult());
    }

    @Test
    void submitResponseDoesNotRewindAJobThatAlreadyStarted() {
        relay.applyWorkerEvent(Map.of("jobId", "j1", "projectId", "P1", "status", "PROCESSING"));

        relay.rememberSubmittedJob("j1", "P1", "Person", "DL_QUERY", Map.of("status", "QUEUED", "queuePosition", 1));

        assertEquals(DLQueryJob.Status.PROCESSING, relay.getJob("j1").getStatus());
    }

    @Test
    void progressEventsStillMoveARunningJobForward() {
        relay.rememberSubmittedJob("j1", "P1", "Person", "DL_QUERY", Map.of("status", "QUEUED", "queuePosition", 2));

        relay.applyWorkerEvent(Map.of("jobId", "j1", "projectId", "P1", "status", "PROCESSING"));
        assertEquals(DLQueryJob.Status.PROCESSING, relay.getJob("j1").getStatus());

        relay.applyWorkerEvent(Map.of("jobId", "j1", "projectId", "P1", "status", "FAILED", "error", "Cancelled"));
        assertEquals(DLQueryJob.Status.FAILED, relay.getJob("j1").getStatus());
        assertEquals("Cancelled", relay.getJob("j1").getError());
    }

    @Test
    void markLostFailsAWaitingJobAndTellsSubscribers() {
        relay.rememberSubmittedJob("j1", "P1", "Person", "DL_QUERY", Map.of("status", "QUEUED", "queuePosition", 1));

        DLQueryJob lost = relay.markLost("j1", "Reasoning worker no longer has this job");

        assertEquals(DLQueryJob.Status.FAILED, lost.getStatus());
        assertEquals("Reasoning worker no longer has this job", relay.getJob("j1").getError());
        verify(messaging, times(2)).convertAndSend(eq("/topic/dlquery/j1"), any(Object.class));
    }

    @Test
    void markLostLeavesAFinishedJobAlone() {
        relay.applyWorkerEvent(Map.of("jobId", "j1", "projectId", "P1", "status", "COMPLETED",
                "result", Map.of("success", true)));

        DLQueryJob job = relay.markLost("j1", "Reasoning worker no longer has this job");

        assertEquals(DLQueryJob.Status.COMPLETED, job.getStatus());
        verify(messaging, times(1)).convertAndSend(eq("/topic/dlquery/j1"), any(Object.class));
    }

    @Test
    void markLostIgnoresUnknownJobs() {
        assertNull(relay.markLost("missing", "gone"));
        verify(messaging, never()).convertAndSend(anyString(), any(Object.class));
    }

    static EditorJobResultRetention mockRetention(Map<String, DLQueryJob> retained) {
        EditorJobResultRetention retention = mock(EditorJobResultRetention.class);
        when(retention.get(anyString())).thenAnswer(invocation -> Optional.ofNullable(retained.get(invocation.getArgument(0))));
        doAnswer(invocation -> {
            DLQueryJob job = invocation.getArgument(0);
            retained.put(job.getJobId(), job);
            return null;
        }).when(retention).retain(any());
        return retention;
    }
}
