package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException;
import self.research.ontology.reasoner.model.ReasoningJob;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

class SnapshotJobFlowTest {

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
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
