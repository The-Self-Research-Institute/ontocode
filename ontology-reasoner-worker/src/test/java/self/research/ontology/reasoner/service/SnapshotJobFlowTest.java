package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException;
import self.research.ontology.reasoner.model.ReasoningJob;
import self.research.ontology.reasoner.repository.ReasoningJobRecordRepository;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SnapshotJobFlowTest {

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void cachedSnapshotSubmitIsAnsweredCompletedWithoutQueueing() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor()
                .projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(sessions);
        snapshots.snapshot("P1", "OPENLLET", null);
        ReasoningJobRecordRepository records = mock(ReasoningJobRecordRepository.class);
        ReasoningJobStore jobStore = new ReasoningJobStore(records, 1_000_000, 72);
        ReasoningQueueManagerTest.RecordingSender sender = new ReasoningQueueManagerTest.RecordingSender();
        ReasoningQueueManager manager = new ReasoningQueueManager(editor.client,
                QueueTestSupport.policy(editor.client), QueueTestSupport.retention(), jobStore, sender, snapshots);
        clearInvocations(editor.client);

        ReasoningJob job = manager.enqueue(ReasoningJob.JobType.REASONER_SNAPSHOT, "P1", null, null, "openllet", null);

        assertEquals(ReasoningJob.Status.COMPLETED, job.getStatus());
        assertEquals(true, job.getResult().get("cached"));
        assertEquals(true, job.getResult().get("consistent"));
        assertEquals("OPENLLET", job.getResult().get("requestedReasoner"));
        assertEquals(0, manager.queuedJobCount());
        assertEquals(0, manager.activeJobCount());
        assertEquals(1, snapshots.buildCount());
        verify(editor.client, times(1)).getTripleCount("P1");
        assertEquals(editor.tripleCount("P1"), job.getTripleCount());
        assertEquals(List.of(job.getJobId() + ":COMPLETED:0"), sender.events);
        ReasoningJob found = manager.getJob(job.getJobId());
        assertEquals(ReasoningJob.Status.COMPLETED, found.getStatus());
        assertEquals(job.getResult(), found.getResult());
        verify(records, timeout(5_000)).save(argThat(record -> job.getJobId().equals(record.getJobId())
                && "COMPLETED".equals(record.getStatus()) && record.getResultJson() != null));
        assertEquals(ReasoningQueueManager.CancelOutcome.ALREADY_FINISHED, manager.cancel(job.getJobId()).outcome());

        ReasoningJob other = manager.enqueue(ReasoningJob.JobType.REASONER_SNAPSHOT, "P1", null, null, "OPENLLET", null);
        assertNotEquals(job.getJobId(), other.getJobId());
        assertEquals(ReasoningJob.Status.COMPLETED, other.getStatus());
        manager.shutdown();
        jobStore.shutdown();
    }

    @Test
    void snapshotSubmitIsQueuedWhenNothingIsCachedOrItIsForADraft() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor()
                .projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(sessions);
        ReasoningQueueManager manager = new ReasoningQueueManager(editor.client,
                QueueTestSupport.policy(editor.client), QueueTestSupport.retention(), ReasoningJobStore.disabled(),
                new ReasoningQueueManagerTest.RecordingSender(), snapshots);

        ReasoningJob uncached = manager.enqueue(ReasoningJob.JobType.REASONER_SNAPSHOT, "P1", null, null, "OPENLLET", null);
        assertEquals(ReasoningJob.Status.QUEUED, uncached.getStatus());

        snapshots.snapshot("P1", "OPENLLET", null);
        ReasoningJob draft = manager.enqueue(ReasoningJob.JobType.REASONER_SNAPSHOT, "P1", null, null, "OPENLLET",
                "alice@example.com");
        assertEquals(ReasoningJob.Status.QUEUED, draft.getStatus());

        ReasoningJob otherReasoner = manager.enqueue(ReasoningJob.JobType.REASONER_SNAPSHOT, "P1", null, null, "ELK", null);
        assertEquals(ReasoningJob.Status.QUEUED, otherReasoner.getStatus());

        ReasoningJob legacy = manager.enqueue(ReasoningJob.JobType.REASONER_HIERARCHY, "P1", null, null, "OPENLLET", null);
        assertEquals(ReasoningJob.Status.QUEUED, legacy.getStatus());

        editor.project("P1", SnapshotTestSupport.CONSISTENT);
        ReasoningJob changedSize = manager.enqueue(ReasoningJob.JobType.REASONER_SNAPSHOT, "P1", null, null, "OPENLLET", null);
        assertEquals(ReasoningJob.Status.QUEUED, changedSize.getStatus());
        manager.shutdown();
    }

    @Test
    void snapshotSubmitAtRevisionZeroIsAlwaysQueued() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor()
                .projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA)
                .revision("P1", 0);
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        ReasoningSnapshotService snapshots = SnapshotTestSupport.snapshots(sessions);
        ReasoningQueueManager manager = new ReasoningQueueManager(editor.client,
                QueueTestSupport.policy(editor.client), QueueTestSupport.retention(), ReasoningJobStore.disabled(),
                new ReasoningQueueManagerTest.RecordingSender(), snapshots);

        snapshots.snapshot("P1", "OPENLLET", null);
        ReasoningJob job = manager.enqueue(ReasoningJob.JobType.REASONER_SNAPSHOT, "P1", null, null, "OPENLLET", null);

        assertEquals(ReasoningJob.Status.QUEUED, job.getStatus());
        manager.shutdown();
    }

    @Test
    void cancellingTheJobThatBuildsTheSnapshotLetsTheWaitingJobBuildIt() throws Exception {
        SnapshotTestSupport.FakeEditor editor = new SnapshotTestSupport.FakeEditor()
                .projectWithoutSwrl("P1", SnapshotTestSupport.PIZZA);
        OntologySessionService sessions = SnapshotTestSupport.sessions(editor);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger opened = new AtomicInteger();
        doAnswer(invocation -> {
            if (opened.incrementAndGet() == 1) {
                started.countDown();
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ReasonerInterruptedException(e);
                }
            }
            return invocation.callRealMethod();
        }).when(sessions).openSnapshotSession(any(), any(), any());
        ReasoningJobExecutor executor = new ReasoningJobExecutor(sessions, SnapshotTestSupport.snapshots(sessions));
        ReasoningQueueManager manager = QueueTestSupport.queueManager(editor.client);
        ReasoningQueueProcessor processor = new ReasoningQueueProcessor(manager, executor, pool,
                new RunawayReasoningHealthIndicator(), 120_000, 5_000);

        ReasoningJob builder = manager.enqueue(ReasoningJob.JobType.REASONER_SNAPSHOT, "P1", null, null, "OPENLLET", null);
        processor.processNext();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        ReasoningJob waiter = manager.enqueue(ReasoningJob.JobType.REASONER_HIERARCHY, "P1", null, null, "OPENLLET", null);
        processor.processNext();
        assertTrue(ReasoningQueueProcessorTest.waitUntil(
                () -> manager.getJob(waiter.getJobId()).getStatus() == ReasoningJob.Status.PROCESSING, 10_000));
        Thread.sleep(300);

        assertEquals(ReasoningQueueManager.CancelOutcome.CANCELLED_RUNNING, processor.cancel(builder.getJobId()).outcome());

        assertTrue(ReasoningQueueProcessorTest.waitUntil(
                () -> manager.getJob(waiter.getJobId()).getStatus() == ReasoningJob.Status.COMPLETED, 60_000));
        ReasoningJob finished = manager.getJob(waiter.getJobId());
        assertEquals(true, finished.getResult().get("success"));
        assertTrue(((List<?>) finished.getResult().get("hierarchy")).size() >= 1);
        assertEquals(ReasoningJob.Status.FAILED, manager.getJob(builder.getJobId()).getStatus());
        assertEquals(ReasoningQueueManager.CANCELLED_MESSAGE, manager.getJob(builder.getJobId()).getError());
        assertEquals(2, opened.get());
    }
}
