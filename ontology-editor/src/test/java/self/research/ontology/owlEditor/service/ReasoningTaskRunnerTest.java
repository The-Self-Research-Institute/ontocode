package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import self.research.ontology.common.admission.Estimates;
import self.research.ontology.common.admission.ReservationLedger;
import self.research.ontology.common.admission.WorkKind;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class ReasoningTaskRunnerTest {

    private static final String THREAD = "runner-test-worker";

    private ReasoningTaskRunner runner;

    @AfterEach
    void tearDown() {
        if (runner != null) {
            runner.shutdown();
        }
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static long liveWorkers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> THREAD.equals(t.getName()) && t.isAlive()).count();
    }

    @Test
    void aCooperativeTaskStopsOnTimeoutAndItsCleanupRunsAtOnce() throws Exception {
        runner = new ReasoningTaskRunner(2, 2, 2_000, THREAD);
        CountDownLatch started = new CountDownLatch(1);
        ReasoningTaskRunner.Execution<String> execution = runner.submit("cooperative", context -> {
            started.countDown();
            Thread.sleep(60_000);
            return "late";
        });
        started.await();
        AtomicBoolean cleaned = new AtomicBoolean();
        ReasoningTaskRunner.afterAll(List.of(execution), () -> cleaned.set(true));

        assertThrows(TimeoutException.class, () -> execution.await(50));

        assertTrue(waitFor(cleaned::get));
        assertTrue(execution.stopped());
        assertEquals(0, runner.uncooperativeTasks());
    }

    @Test
    void anUncooperativeTaskKeepsItsReservationUntilItEnds() throws Exception {
        runner = new ReasoningTaskRunner(2, 2, 100, THREAD);
        ReservationLedger ledger = new FakeMemorySignals().ledger();
        HeavyWorkAdmission admission = new HeavyWorkAdmission(ledger, new Estimates());
        HeavyWorkAdmission.Hold hold = admission.admit("someone", WorkKind.DL_QUERY, 4L * 1024 * 1024);
        long reserved = ledger.reservedBytes();
        assertTrue(reserved > 0);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        OWLReasoner reasoner = mock(OWLReasoner.class);
        ReasoningTaskRunner.Execution<String> execution = runner.submit("stubborn", context -> {
            context.attach(reasoner);
            started.countDown();
            while (true) {
                try {
                    if (finish.await(10, TimeUnit.MILLISECONDS)) {
                        return "done";
                    }
                } catch (InterruptedException ignoredInterrupt) {
                }
            }
        });
        started.await();
        AtomicBoolean released = new AtomicBoolean();
        ReasoningTaskRunner.afterAll(List.of(execution), () -> {
            hold.close();
            released.set(true);
        });

        assertThrows(TimeoutException.class, () -> execution.await(50));

        verify(reasoner, timeout(5_000)).interrupt();
        assertFalse(execution.stopped());
        assertTrue(execution.uncooperative());
        assertEquals(1, runner.uncooperativeTasks());
        assertFalse(released.get());
        assertEquals(reserved, ledger.reservedBytes());

        finish.countDown();

        assertTrue(waitFor(released::get));
        assertEquals(0, ledger.reservedBytes());
        assertEquals(0, runner.uncooperativeTasks());
    }

    @Test
    void aTaskThatIgnoresTheInterruptThenHonoursItIsReleasedAfterwards() throws Exception {
        runner = new ReasoningTaskRunner(2, 2, 100, THREAD);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger interrupts = new AtomicInteger();
        AtomicBoolean honour = new AtomicBoolean();
        ReasoningTaskRunner.Execution<String> execution = runner.submit("late-honour", context -> {
            started.countDown();
            while (true) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    interrupts.incrementAndGet();
                    if (honour.get()) {
                        return "stopped";
                    }
                }
            }
        });
        started.await();
        AtomicBoolean cleaned = new AtomicBoolean();
        ReasoningTaskRunner.afterAll(List.of(execution), () -> cleaned.set(true));

        assertThrows(TimeoutException.class, () -> execution.await(30));
        assertFalse(cleaned.get());
        assertEquals(1, runner.uncooperativeTasks());

        honour.set(true);
        execution.cancel();

        assertTrue(waitFor(cleaned::get));
        assertEquals(0, runner.uncooperativeTasks());
    }

    @Test
    void whenThePoolAndQueueAreFullTheSharedBusyMessageIsUsed() throws Exception {
        runner = new ReasoningTaskRunner(1, 1, 100, THREAD);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        runner.submit("running", context -> {
            started.countDown();
            release.await();
            return null;
        });
        started.await();
        runner.submit("queued", context -> {
            release.await();
            return null;
        });

        BusyException busy = assertThrows(BusyException.class, () -> runner.submit("refused", context -> null));

        assertEquals(ReasoningTaskRunner.BUSY_MESSAGE, busy.getMessage());
        assertTrue(busy.retryAfterMillis() > 0);
        release.countDown();
    }

    @Test
    void aTaskCancelledBeforeItStartsIsReportedAsStoppedWithoutRunning() throws Exception {
        runner = new ReasoningTaskRunner(1, 4, 100, THREAD);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        runner.submit("blocker", context -> {
            started.countDown();
            release.await();
            return null;
        });
        started.await();
        AtomicBoolean ran = new AtomicBoolean();
        ReasoningTaskRunner.Execution<String> queued = runner.submit("queued", context -> {
            ran.set(true);
            return "x";
        });
        AtomicBoolean cleaned = new AtomicBoolean();
        ReasoningTaskRunner.afterAll(List.of(queued), () -> cleaned.set(true));

        assertThrows(TimeoutException.class, () -> queued.await(20));

        assertTrue(cleaned.get());
        assertTrue(queued.stopped());
        release.countDown();
        Thread.sleep(100);
        assertFalse(ran.get());
    }

    @Test
    void noWorkerThreadsLeakAfterTheRunnerIsShutDown() throws Exception {
        runner = new ReasoningTaskRunner(3, 3, 100, THREAD);
        for (int i = 0; i < 20; i++) {
            ReasoningTaskRunner.Execution<Integer> execution = runner.submit("quick", context -> 1);
            assertEquals(1, execution.await(5_000));
        }
        assertTrue(liveWorkers() > 0);

        runner.shutdown();

        assertTrue(waitFor(() -> liveWorkers() == 0));
    }

    @Test
    void theDlQueueRefusesNewJobsPastItsCap() {
        DLQueryQueueManager manager = new DLQueryQueueManager(mock(SimpMessagingTemplate.class),
                mock(SparqlDatasetService.class), new DLQueryConcurrencyPolicy(mock(ImportQueueManager.class)));
        manager.useMaxQueued(2);
        manager.enqueue("p1", "A", List.of("instances"), null, null);
        manager.enqueue("p1", "B", List.of("instances"), null, null);

        BusyException busy = assertThrows(BusyException.class,
                () -> manager.enqueue("p1", "C", List.of("instances"), null, null));

        assertEquals(ReasoningTaskRunner.BUSY_MESSAGE, busy.getMessage());
        assertEquals("A", manager.enqueue("p1", "A", List.of("instances"), null, null).getExpression());
    }
}
