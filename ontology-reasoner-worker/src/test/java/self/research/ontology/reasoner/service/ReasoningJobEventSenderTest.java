package self.research.ontology.reasoner.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningJobEventSenderTest {

    @Test
    void terminalEventsSurviveAFullQueueWhileOlderProgressUpdatesAreDropped() throws Exception {
        BlockedPublisher publisher = new BlockedPublisher();
        ReasoningJobEventSender sender = new ReasoningJobEventSender(publisher::publish, 2);
        sender.submit(snapshot("blocker", "PROCESSING"));
        assertTrue(publisher.firstCallStarted.await(5, TimeUnit.SECONDS));

        sender.submit(snapshot("a", "QUEUED"));
        sender.submit(snapshot("b", "QUEUED"));
        sender.submit(snapshot("c", "COMPLETED"));
        sender.submit(snapshot("d", "FAILED"));
        sender.submit(snapshot("e", "FAILED"));
        sender.submit(snapshot("f", "QUEUED"));
        publisher.release.countDown();
        sender.shutdown(5_000);

        assertEquals(List.of("blocker:PROCESSING", "c:COMPLETED", "d:FAILED", "e:FAILED"), publisher.sent);
    }

    @Test
    void progressUpdatesForTheSameJobAreCoalescedWithoutReorderingThem() throws Exception {
        BlockedPublisher publisher = new BlockedPublisher();
        ReasoningJobEventSender sender = new ReasoningJobEventSender(publisher::publish, 100);
        sender.submit(snapshot("blocker", "PROCESSING"));
        assertTrue(publisher.firstCallStarted.await(5, TimeUnit.SECONDS));

        sender.submit(snapshot("a", "QUEUED"));
        sender.submit(snapshot("b", "QUEUED"));
        sender.submit(snapshot("a", "PROCESSING"));
        sender.submit(snapshot("a", "COMPLETED"));
        sender.submit(snapshot("b", "PROCESSING"));
        sender.submit(snapshot("a", "PROCESSING"));
        publisher.release.countDown();
        sender.shutdown(5_000);

        assertEquals(List.of("blocker:PROCESSING", "a:PROCESSING", "b:PROCESSING", "a:COMPLETED"), publisher.sent);
    }

    @Test
    void shutdownDrainsPendingEventsBeforeStopping() throws Exception {
        BlockedPublisher publisher = new BlockedPublisher();
        ReasoningJobEventSender sender = new ReasoningJobEventSender(publisher::publish, 100);
        sender.submit(snapshot("blocker", "PROCESSING"));
        assertTrue(publisher.firstCallStarted.await(5, TimeUnit.SECONDS));
        sender.submit(snapshot("a", "COMPLETED"));
        publisher.release.countDown();

        sender.shutdown(5_000);
        sender.submit(snapshot("late", "COMPLETED"));

        assertEquals(List.of("blocker:PROCESSING", "a:COMPLETED"), publisher.sent);
        assertEquals(0, sender.pendingCount());
    }

    static ReasoningJobNotifier.JobSnapshot snapshot(String jobId, String status) {
        return new ReasoningJobNotifier.JobSnapshot(jobId, "REASONER_CLASSIFY", "P1", status, 0, 0L,
                null, 0L, null, null, null, null);
    }

    private static final class BlockedPublisher {
        final CountDownLatch firstCallStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> sent = new CopyOnWriteArrayList<>();

        void publish(ReasoningJobNotifier.JobSnapshot snapshot) {
            firstCallStarted.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sent.add(snapshot.jobId() + ":" + snapshot.status());
        }
    }
}
