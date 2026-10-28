package self.research.ontology.swrl.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionGateTest {

    private final ExecutorService callers = Executors.newCachedThreadPool();
    private ExecutionGate gate;

    @AfterEach
    void stop() {
        callers.shutdownNow();
        if (gate != null) {
            gate.shutdown();
        }
    }

    private static Callable<String> blockedOn(CountDownLatch release, CountDownLatch entered) {
        return () -> {
            if (entered != null) {
                entered.countDown();
            }
            release.await();
            return "done";
        };
    }

    private Future<String> submit(String owner, Callable<String> task, long timeoutSeconds) {
        return callers.submit(() -> gate.call(owner, task, timeoutSeconds));
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < end, "condition not reached");
            Thread.sleep(10);
        }
    }

    @Test
    void aNormalTaskRunsAndReturns() throws Exception {
        gate = new ExecutionGate(2, 10, 1, 1, 1000);

        assertEquals("ok", gate.call("user:a", () -> "ok", 5));
        assertEquals(1, gate.stats().completed());
    }

    @Test
    void aFullQueueIsRefusedWithQueueFull() throws Exception {
        gate = new ExecutionGate(1, 1, 1, 1, 5000);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        Future<String> first = submit("user:a", blockedOn(release, entered), 30);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        Future<String> second = submit("user:b", blockedOn(release, null), 30);
        waitUntil(() -> gate.stats().queued() == 1);

        SwrlBusyException refused = assertThrows(SwrlBusyException.class, () -> gate.call("user:c", () -> "x", 5));

        assertEquals(ExecutionGate.QUEUE_FULL, refused.getReason());
        assertEquals(1, gate.stats().refusedQueueFull());
        release.countDown();
        assertEquals("done", first.get(5, TimeUnit.SECONDS));
        assertEquals("done", second.get(5, TimeUnit.SECONDS));
    }

    @Test
    void anOwnerGetsOneRunningAndOneWaitingAndNoMore() throws Exception {
        gate = new ExecutionGate(3, 10, 1, 1, 5000);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        Future<String> running = submit("user:a", blockedOn(release, entered), 30);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        Future<String> waiting = submit("user:a", blockedOn(release, null), 30);
        waitUntil(() -> gate.stats().active() == 2);

        SwrlBusyException refused = assertThrows(SwrlBusyException.class, () -> gate.call("user:a", () -> "x", 5));
        assertEquals(ExecutionGate.OWNER_LIMIT, refused.getReason());

        assertEquals("other", gate.call("user:b", () -> "other", 5));
        release.countDown();
        assertEquals("done", running.get(5, TimeUnit.SECONDS));
        assertEquals("done", waiting.get(5, TimeUnit.SECONDS));
        assertEquals(1, gate.stats().refusedOwnerLimit());
    }

    @Test
    void aTaskWithoutAnOwnerIsNotLimitedPerOwner() throws Exception {
        gate = new ExecutionGate(2, 10, 1, 0, 1000);

        assertEquals("a", gate.call(null, () -> "a", 5));
        assertEquals("b", gate.call(null, () -> "b", 5));
    }

    @Test
    void aTaskThatWaitsTooLongForAWorkerIsRefusedWithWaitTimeoutAndNeverRuns() throws Exception {
        gate = new ExecutionGate(1, 5, 1, 1, 300);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        Future<String> first = submit("user:a", blockedOn(release, entered), 30);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        boolean[] ran = {false};

        SwrlBusyException refused = assertThrows(SwrlBusyException.class, () -> gate.call("user:b", () -> {
            ran[0] = true;
            return "x";
        }, 5));

        assertEquals(ExecutionGate.WAIT_TIMEOUT, refused.getReason());
        assertEquals(0, gate.stats().queued());
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        Thread.sleep(100);
        assertEquals(false, ran[0]);
        assertEquals(1, gate.stats().refusedWaitTimeout());
        assertEquals("again", gate.call("user:b", () -> "again", 5));
    }

    @Test
    void aTaskThatIgnoresInterruptionKeepsItsWorkerAfterTheTimeoutAndIsReported() throws Exception {
        gate = new ExecutionGate(1, 5, 1, 1, 300);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        Callable<String> stubborn = () -> {
            entered.countDown();
            while (finish.getCount() > 0) {
                try {
                    finish.await();
                } catch (InterruptedException ignored) {
                }
            }
            return "late";
        };

        assertThrows(TimeoutException.class, () -> gate.call("user:a", stubborn, 1));

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        ExecutionGate.Stats during = gate.stats();
        assertEquals(1, during.active());
        assertTrue(during.longestRunningMillis() >= 900, "age was " + during.longestRunningMillis());
        SwrlBusyException refused = assertThrows(SwrlBusyException.class, () -> gate.call("user:b", () -> "x", 5));
        assertEquals(ExecutionGate.WAIT_TIMEOUT, refused.getReason());
        assertEquals(1, gate.stats().active());

        finish.countDown();
        waitUntil(() -> gate.stats().active() == 0);
        assertEquals("fresh", gate.call("user:b", () -> "fresh", 5));
    }
}
