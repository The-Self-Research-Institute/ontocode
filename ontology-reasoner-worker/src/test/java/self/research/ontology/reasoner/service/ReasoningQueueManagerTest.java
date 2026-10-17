package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.reasoner.model.ReasoningJob;
import self.research.ontology.reasoner.model.ReasoningJobRecord;
import self.research.ontology.reasoner.repository.ReasoningJobRecordRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReasoningQueueManagerTest {

    @Test
    void identicalRequestReusesAJobOnlyWhileItIsStillQueued() {
        ReasoningQueueManager manager = QueueTestSupport.queueManager();
        ReasoningJob first = QueueTestSupport.enqueue(manager, "P1");
        assertSame(first, QueueTestSupport.enqueue(manager, "P1"));

        assertSame(first, manager.dequeue());

        ReasoningJob afterStart = QueueTestSupport.enqueue(manager, "P1");
        assertNotEquals(first.getJobId(), afterStart.getJobId());
    }

    @Test
    void queuedJobIsSharedOnlyByRequestsFromTheSameOwner() {
        ReasoningQueueManager manager = QueueTestSupport.queueManager();
        ReasoningJob alice = manager.enqueue(ReasoningJob.JobType.DL_QUERY, "P1", "Pizza",
                List.of("subclasses"), null, "alice@example.com");
        ReasoningJob bob = manager.enqueue(ReasoningJob.JobType.DL_QUERY, "P1", "Pizza",
                List.of("subclasses"), null, "bob@example.com");
        ReasoningJob published = manager.enqueue(ReasoningJob.JobType.DL_QUERY, "P1", "Pizza",
                List.of("subclasses"), null, null);

        assertNotEquals(alice.getJobId(), bob.getJobId());
        assertNotEquals(alice.getJobId(), published.getJobId());
        assertNotEquals(bob.getJobId(), published.getJobId());
        assertSame(alice, manager.enqueue(ReasoningJob.JobType.DL_QUERY, "P1", "Pizza",
                List.of("subclasses"), null, "alice@example.com"));
        assertSame(published, manager.enqueue(ReasoningJob.JobType.DL_QUERY, "P1", "Pizza",
                List.of("subclasses"), null, " "));
    }

    @Test
    void timedOutJobIsNeverHandedBackForANewRequest() {
        ReasoningQueueManager manager = QueueTestSupport.queueManager();
        ReasoningJob first = QueueTestSupport.enqueue(manager, "P1");
        manager.dequeue();
        manager.reportTimedOut(first, "Reasoning timed out");

        ReasoningJob retry = QueueTestSupport.enqueue(manager, "P1");

        assertNotEquals(first.getJobId(), retry.getJobId());
        assertEquals(ReasoningJob.Status.QUEUED, retry.getStatus());
    }

    @Test
    void jobCanBeLookedUpThroughEveryStage() {
        ReasoningQueueManager manager = QueueTestSupport.queueManager();
        ReasoningJob job = QueueTestSupport.enqueue(manager, "P1");
        assertEquals(ReasoningJob.Status.QUEUED, manager.getJob(job.getJobId()).getStatus());

        manager.dequeue();
        assertEquals(ReasoningJob.Status.PROCESSING, manager.getJob(job.getJobId()).getStatus());

        manager.reportTimedOut(job, "Reasoning timed out");
        assertEquals(1, manager.activeJobCount());
        assertEquals(ReasoningJob.Status.FAILED, manager.getJob(job.getJobId()).getStatus());

        manager.releaseAbandonedSlot(job.getJobId());
        assertEquals(0, manager.activeJobCount());
        assertNotNull(manager.getJob(job.getJobId()));
    }

    @Test
    void completedJobIsFoundInRetentionAfterItLeavesTheActiveSet() {
        ReasoningQueueManager manager = QueueTestSupport.queueManager();
        ReasoningJob job = QueueTestSupport.enqueue(manager, "P1");
        manager.dequeue();

        manager.markCompleted(job, Map.of("success", true), 10);

        assertEquals(0, manager.activeJobCount());
        assertEquals(ReasoningJob.Status.COMPLETED, manager.getJob(job.getJobId()).getStatus());
    }

    @Test
    void eachJobGetsOneQueuedEventAndOnlyMovedJobsGetPositionUpdates() {
        RecordingSender sender = new RecordingSender();
        EditorClient editorClient = QueueTestSupport.editorClient();
        ReasoningQueueManager manager = new ReasoningQueueManager(editorClient,
                QueueTestSupport.policy(editorClient), QueueTestSupport.retention(), ReasoningJobStore.disabled(), sender);

        ReasoningJob first = QueueTestSupport.enqueue(manager, "P1");
        ReasoningJob second = QueueTestSupport.enqueue(manager, "P2");
        ReasoningJob third = QueueTestSupport.enqueue(manager, "P3");
        QueueTestSupport.enqueue(manager, "P3");

        assertEquals(List.of(
                first.getJobId() + ":QUEUED:1",
                second.getJobId() + ":QUEUED:2",
                third.getJobId() + ":QUEUED:3"), sender.events);

        sender.events.clear();
        manager.dequeue();

        assertEquals(List.of(
                second.getJobId() + ":QUEUED:1",
                third.getJobId() + ":QUEUED:2",
                first.getJobId() + ":PROCESSING:0"), sender.events);
        manager.shutdown();
    }

    @Test
    void cancellingAQueuedJobFailsItAndMovesTheRestUp() {
        ReasoningQueueManager manager = QueueTestSupport.queueManager();
        ReasoningJob first = QueueTestSupport.enqueue(manager, "P1");
        ReasoningJob second = QueueTestSupport.enqueue(manager, "P2");

        ReasoningQueueManager.CancelResult result = manager.cancel(first.getJobId());

        assertEquals(ReasoningQueueManager.CancelOutcome.CANCELLED_QUEUED, result.outcome());
        assertEquals(ReasoningJob.Status.FAILED, manager.getJob(first.getJobId()).getStatus());
        assertEquals(ReasoningQueueManager.CANCELLED_MESSAGE, manager.getJob(first.getJobId()).getError());
        assertEquals(1, second.getQueuePosition());
        assertEquals(1, manager.queuedJobCount());
        assertSame(second, manager.dequeue());
    }

    @Test
    void cancellingARunningJobKeepsItsSlotUntilTheWorkStops() {
        ReasoningQueueManager manager = QueueTestSupport.queueManager();
        ReasoningJob job = QueueTestSupport.enqueue(manager, "P1");
        manager.dequeue();

        ReasoningQueueManager.CancelResult result = manager.cancel(job.getJobId());
        manager.markCompleted(job, Map.of("success", true), 5);

        assertEquals(ReasoningQueueManager.CancelOutcome.CANCELLED_RUNNING, result.outcome());
        assertEquals(ReasoningJob.Status.FAILED, manager.getJob(job.getJobId()).getStatus());
        assertEquals(ReasoningQueueManager.CANCELLED_MESSAGE, manager.getJob(job.getJobId()).getError());
        assertEquals(0, manager.activeJobCount());
    }

    @Test
    void sharedQueuedJobIsOnlyCancelledOnceEveryRequesterHasCancelled() {
        ReasoningQueueManager manager = QueueTestSupport.queueManager();
        ReasoningJob job = QueueTestSupport.enqueue(manager, "P1");
        assertSame(job, QueueTestSupport.enqueue(manager, "P1"));
        assertEquals(2, job.getRequesterCount());

        ReasoningQueueManager.CancelResult first = manager.cancel(job.getJobId());

        assertEquals(ReasoningQueueManager.CancelOutcome.STILL_REQUESTED, first.outcome());
        assertEquals(ReasoningJob.Status.QUEUED, manager.getJob(job.getJobId()).getStatus());
        assertEquals(1, manager.queuedJobCount());

        ReasoningQueueManager.CancelResult second = manager.cancel(job.getJobId());

        assertEquals(ReasoningQueueManager.CancelOutcome.CANCELLED_QUEUED, second.outcome());
        assertEquals(ReasoningJob.Status.FAILED, manager.getJob(job.getJobId()).getStatus());
        assertEquals(0, manager.queuedJobCount());
    }

    @Test
    void sharedRunningJobKeepsRunningUntilTheLastRequesterCancels() {
        ReasoningQueueManager manager = QueueTestSupport.queueManager();
        ReasoningJob job = QueueTestSupport.enqueue(manager, "P1");
        QueueTestSupport.enqueue(manager, "P1");
        QueueTestSupport.enqueue(manager, "P1");
        manager.dequeue();

        assertEquals(ReasoningQueueManager.CancelOutcome.STILL_REQUESTED, manager.cancel(job.getJobId()).outcome());
        assertEquals(ReasoningQueueManager.CancelOutcome.STILL_REQUESTED, manager.cancel(job.getJobId()).outcome());
        assertEquals(ReasoningJob.Status.PROCESSING, manager.getJob(job.getJobId()).getStatus());

        assertEquals(ReasoningQueueManager.CancelOutcome.CANCELLED_RUNNING, manager.cancel(job.getJobId()).outcome());
        assertEquals(ReasoningJob.Status.FAILED, manager.getJob(job.getJobId()).getStatus());
    }

    @Test
    void cancellingAFinishedSharedJobChangesNothing() {
        ReasoningQueueManager manager = QueueTestSupport.queueManager();
        ReasoningJob job = QueueTestSupport.enqueue(manager, "P1");
        QueueTestSupport.enqueue(manager, "P1");
        manager.dequeue();
        manager.markCompleted(job, Map.of("success", true), 5);

        assertEquals(ReasoningQueueManager.CancelOutcome.ALREADY_FINISHED, manager.cancel(job.getJobId()).outcome());
        assertEquals(ReasoningJob.Status.COMPLETED, manager.getJob(job.getJobId()).getStatus());
    }

    @Test
    void cancellingAnUnknownJobReportsNotFound() {
        ReasoningQueueManager manager = QueueTestSupport.queueManager();

        assertEquals(ReasoningQueueManager.CancelOutcome.NOT_FOUND, manager.cancel("missing").outcome());
    }

    @Test
    void projectCannotQueueMoreThanTheConfiguredNumberOfJobs() {
        ReasoningQueueManager manager = QueueTestSupport.queueManager();
        ReflectionTestUtils.setField(manager, "maxQueuedPerProject", 2);
        manager.enqueue(ReasoningJob.JobType.REASONER_CLASSIFY, "P1", null, null, "HERMIT", null);
        manager.enqueue(ReasoningJob.JobType.REASONER_REALIZE, "P1", null, null, "HERMIT", null);

        assertSame(manager.getJob(QueueTestSupport.enqueue(manager, "P1").getJobId()),
                QueueTestSupport.enqueue(manager, "P1"));
        assertThrows(ReasoningQueueManager.ProjectQueueFullException.class, () ->
                manager.enqueue(ReasoningJob.JobType.REASONER_CONSISTENCY, "P1", null, null, "HERMIT", null));
        assertNotNull(QueueTestSupport.enqueue(manager, "P2"));
    }

    @Test
    void jobMissingFromMemoryIsReadBackFromTheStore() {
        ReasoningJobRecordRepository repository = mock(ReasoningJobRecordRepository.class);
        when(repository.findById("old-job")).thenReturn(Optional.of(ReasoningJobRecord.builder()
                .jobId("old-job")
                .jobType("REASONER_CLASSIFY")
                .status("FAILED")
                .error(ReasoningJobStore.RESTART_MESSAGE)
                .build()));
        EditorClient editorClient = QueueTestSupport.editorClient();
        ReasoningQueueManager manager = new ReasoningQueueManager(editorClient,
                QueueTestSupport.policy(editorClient), QueueTestSupport.retention(),
                new ReasoningJobStore(repository, 1_000_000, 72));

        ReasoningJob job = manager.getJob("old-job");

        assertEquals(ReasoningJob.Status.FAILED, job.getStatus());
        assertEquals(ReasoningJobStore.RESTART_MESSAGE, job.getError());
        assertEquals(ReasoningQueueManager.CancelOutcome.ALREADY_FINISHED, manager.cancel("old-job").outcome());
        manager.shutdown();
    }

    static final class RecordingSender extends ReasoningJobEventSender {
        final List<String> events = new CopyOnWriteArrayList<>();

        RecordingSender() {
            super(snapshot -> { }, 10);
        }

        @Override
        synchronized void submit(ReasoningJobNotifier.JobSnapshot snapshot) {
            events.add(snapshot.jobId() + ":" + snapshot.status() + ":" + snapshot.queuePosition());
        }
    }
}
