package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.reasoner.model.ReasoningJob;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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
                QueueTestSupport.policy(editorClient), QueueTestSupport.retention(), sender);

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
