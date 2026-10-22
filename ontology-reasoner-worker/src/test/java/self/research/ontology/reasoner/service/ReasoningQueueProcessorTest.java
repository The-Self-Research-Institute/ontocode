package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import self.research.ontology.reasoner.model.ReasoningJob;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReasoningQueueProcessorTest {

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void timedOutJobKeepsItsSlotAndMarksTheWorkerUnhealthyUntilItsThreadReallyEnds() throws Exception {
        ReasoningQueueManager manager = QueueTestSupport.queueManager();
        CountDownLatch release = new CountDownLatch(1);
        ReasoningJobExecutor executor = mock(ReasoningJobExecutor.class);
        when(executor.execute(any())).thenAnswer(invocation -> {
            while (true) {
                try {
                    if (release.await(10, TimeUnit.MILLISECONDS)) {
                        return Map.of("success", true);
                    }
                } catch (InterruptedException ignored) {
                }
            }
        });
        RunawayReasoningHealthIndicator health = new RunawayReasoningHealthIndicator();
        ReasoningQueueProcessor processor = new ReasoningQueueProcessor(manager, executor, pool, health, 200, 300);

        ReasoningJob job = QueueTestSupport.enqueue(manager, "P1");
        processor.processNext();

        assertTrue(waitUntil(() -> health.health().getStatus().equals(Status.DOWN), 5_000));
        assertEquals(1, manager.activeJobCount());
        assertEquals(ReasoningJob.Status.FAILED, manager.getJob(job.getJobId()).getStatus());

        release.countDown();

        assertTrue(waitUntil(() -> manager.activeJobCount() == 0, 5_000));
        assertEquals(Status.UP, health.health().getStatus());
        assertEquals(ReasoningJob.Status.FAILED, manager.getJob(job.getJobId()).getStatus());
    }

    @Test
    void finishedJobReleasesItsSlotAndIsMarkedCompleted() throws Exception {
        ReasoningQueueManager manager = QueueTestSupport.queueManager();
        ReasoningJobExecutor executor = mock(ReasoningJobExecutor.class);
        when(executor.execute(any())).thenReturn(Map.of("success", true));
        ReasoningQueueProcessor processor = new ReasoningQueueProcessor(manager, executor, pool,
                new RunawayReasoningHealthIndicator(), 5_000, 5_000);

        ReasoningJob job = QueueTestSupport.enqueue(manager, "P1");
        processor.processNext();

        assertTrue(waitUntil(() -> manager.getJob(job.getJobId()).getStatus() == ReasoningJob.Status.COMPLETED, 5_000));
        assertTrue(waitUntil(() -> manager.activeJobCount() == 0, 5_000));
    }

    @Test
    void cancellingARunningJobInterruptsItAndFreesTheSlotOnceItStops() throws Exception {
        ReasoningQueueManager manager = QueueTestSupport.queueManager();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ReasoningJobExecutor executor = mock(ReasoningJobExecutor.class);
        when(executor.execute(any())).thenAnswer(invocation -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return Map.of("success", true);
        });
        ReasoningQueueProcessor processor = new ReasoningQueueProcessor(manager, executor, pool,
                new RunawayReasoningHealthIndicator(), 60_000, 5_000);

        ReasoningJob job = QueueTestSupport.enqueue(manager, "P1");
        processor.processNext();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        ReasoningQueueManager.CancelResult result = processor.cancel(job.getJobId());

        assertEquals(ReasoningQueueManager.CancelOutcome.CANCELLED_RUNNING, result.outcome());
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertTrue(waitUntil(() -> manager.activeJobCount() == 0, 5_000));
        assertEquals(ReasoningJob.Status.FAILED, manager.getJob(job.getJobId()).getStatus());
        assertEquals(ReasoningQueueManager.CANCELLED_MESSAGE, manager.getJob(job.getJobId()).getError());
    }

    @Test
    void cancellingASharedRunningJobOnlyInterruptsItForTheLastRequester() throws Exception {
        ReasoningQueueManager manager = QueueTestSupport.queueManager();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ReasoningJobExecutor executor = mock(ReasoningJobExecutor.class);
        when(executor.execute(any())).thenAnswer(invocation -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return Map.of("success", true);
        });
        ReasoningQueueProcessor processor = new ReasoningQueueProcessor(manager, executor, pool,
                new RunawayReasoningHealthIndicator(), 60_000, 5_000);

        ReasoningJob job = QueueTestSupport.enqueue(manager, "P1");
        QueueTestSupport.enqueue(manager, "P1");
        processor.processNext();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        assertEquals(ReasoningQueueManager.CancelOutcome.STILL_REQUESTED, processor.cancel(job.getJobId()).outcome());
        assertFalse(interrupted.await(300, TimeUnit.MILLISECONDS));
        assertEquals(ReasoningJob.Status.PROCESSING, manager.getJob(job.getJobId()).getStatus());

        assertEquals(ReasoningQueueManager.CancelOutcome.CANCELLED_RUNNING, processor.cancel(job.getJobId()).outcome());
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertTrue(waitUntil(() -> manager.activeJobCount() == 0, 5_000));
    }

    static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }
}
