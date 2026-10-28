package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqwrlAdmissionTest {

    private static final String QUERY = "Patient(?p) ^ hasAge(?p, ?a) -> sqwrl:select(?p, ?a)";

    private static ExecutionGate slowGate(int workers, int queue, long sleepMillis) {
        return new ExecutionGate(workers, queue, 10, 10, 10000) {
            @Override
            public <T> T call(String owner, Callable<T> task, long timeoutSeconds)
                    throws java.util.concurrent.TimeoutException, InterruptedException, java.util.concurrent.ExecutionException {
                return super.call(owner, () -> {
                    Thread.sleep(sleepMillis);
                    return task.call();
                }, timeoutSeconds);
            }
        };
    }

    private static void assertDrained(ExecutionGate gate) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while ((gate.stats().active() != 0 || gate.stats().queued() != 0) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, gate.stats().active());
        assertEquals(0, gate.stats().queued());
    }

    @Test
    void theSlotIsReleasedAfterAFailedQuery() throws Exception {
        TriageHarness harness = new TriageHarness();
        ExecutionGate gate = new ExecutionGate(1, 1, 10, 10, 10000);
        ReflectionTestUtils.setField(harness.service, "executionGate", gate);

        SwrlEngineService.SqwrlQueryResult failed = harness.service.executeSqwrlQuery(TriageHarness.PROJECT, "not a query", "bad", null);
        assertFalse(failed.isSuccess());
        assertDrained(gate);

        assertTrue(harness.service.executeSqwrlQuery(TriageHarness.PROJECT, QUERY, "ok", null).isSuccess());
        assertDrained(gate);
        assertEquals(2, gate.stats().completed());
    }

    @Test
    void theSlotIsReleasedAfterATimeout() throws Exception {
        TriageHarness harness = new TriageHarness();
        ReflectionTestUtils.setField(harness.service, "executionTimeoutSeconds", 1);
        ReflectionTestUtils.setField(harness.service, "fetchAllowanceSeconds", 0);
        ExecutionGate gate = slowGate(1, 1, 3000);
        ReflectionTestUtils.setField(harness.service, "executionGate", gate);

        SwrlEngineService.SqwrlQueryResult result = harness.service.executeSqwrlQuery(TriageHarness.PROJECT, QUERY, "slow", null);
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("timed out"));
        assertDrained(gate);
    }

    @Test
    void concurrentCallsBeyondTheWorkersAndQueueGetTheBusyRefusalAndTheGateDrains() throws Exception {
        TriageHarness harness = new TriageHarness();
        assertTrue(harness.service.executeSqwrlQuery(TriageHarness.PROJECT, QUERY, "warm", null).isSuccess());
        ExecutionGate gate = slowGate(1, 1, 1500);
        ReflectionTestUtils.setField(harness.service, "executionGate", gate);
        java.util.concurrent.ConcurrentLinkedQueue<org.semanticweb.owlapi.model.OWLOntology> ready = new java.util.concurrent.ConcurrentLinkedQueue<>();
        for (int i = 0; i < 6; i++) {
            ready.add(harness.fresh());
        }
        org.mockito.Mockito.when(harness.client.fetchOntology(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> ready.poll());
        int callers = 6;
        ExecutorService callersPool = Executors.newFixedThreadPool(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> outcomes = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            outcomes.add(callersPool.submit(() -> {
                go.await();
                try {
                    return harness.service.executeSqwrlQuery(TriageHarness.PROJECT, QUERY, "c", null).isSuccess() ? "ok" : "failed";
                } catch (SwrlBusyException e) {
                    return "busy:" + e.getReason();
                }
            }));
        }
        go.countDown();
        int ok = 0;
        int busy = 0;
        for (Future<String> outcome : outcomes) {
            String value = outcome.get();
            if (value.equals("ok")) {
                ok++;
            } else if (value.equals("busy:" + ExecutionGate.QUEUE_FULL)) {
                busy++;
            }
        }
        callersPool.shutdownNow();
        assertEquals(callers, ok + busy);
        assertTrue(busy >= 1, "busy " + busy);
        assertTrue(ok >= 1 && ok <= 3, "ok " + ok);
        assertEquals(busy, gate.stats().refusedQueueFull());
        assertDrained(gate);
    }
}
