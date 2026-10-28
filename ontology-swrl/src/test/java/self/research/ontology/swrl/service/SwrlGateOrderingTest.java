package self.research.ontology.swrl.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.swrl.dto.ExplainRequest;
import self.research.ontology.swrl.dto.WhyNotRequest;
import self.research.ontology.swrl.model.ExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwrlGateOrderingTest {

    private static final String QUERY = "Patient(?p) ^ hasAge(?p, ?a) -> sqwrl:select(?p, ?a)";
    private static final String RULE = "Patient(?p) ^ hasAge(?p, ?a) -> Child(?p)";
    private static final String OWNER = "owner-1";
    private static final String TOO_LARGE = "This ontology is too large for the rule engine on this server";

    private final ExecutorService blockers = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        OwnerContext.clear();
        blockers.shutdownNow();
    }

    private static void assertDrained(ExecutionGate gate) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while ((gate.stats().active() != 0 || gate.stats().queued() != 0) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, gate.stats().active());
        assertEquals(0, gate.stats().queued());
    }

    private ExecutionGate fullGate() throws Exception {
        return new ExecutionGate(1, 1, 5, 5, 10000);
    }

    private CountDownLatch fill(ExecutionGate gate) throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            String owner = "filler-" + i;
            blockers.submit(() -> gate.call(owner, () -> {
                started.countDown();
                release.await();
                return null;
            }, 60));
            if (i == 0) {
                assertTrue(started.await(3, TimeUnit.SECONDS));
            }
        }
        long deadline = System.currentTimeMillis() + 3000;
        while (gate.stats().queued() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, gate.stats().queued());
        return release;
    }

    private static void warmUp() throws Exception {
        TriageHarness warm = new TriageHarness();
        assertTrue(warm.service.executeSqwrlQuery(TriageHarness.PROJECT, QUERY, "warm", null).isSuccess());
    }

    private interface Action {
        void run(SwrlEngineService service) throws Exception;
    }

    private static List<Action> everyAdmittedPath() {
        List<Action> actions = new ArrayList<>();
        actions.add(service -> service.executeSqwrlQuery(TriageHarness.PROJECT, QUERY, "q", null));
        actions.add(service -> service.executeRules(TriageHarness.PROJECT, null));
        actions.add(service -> service.executeSelectedRules(TriageHarness.PROJECT, List.of("r1")));
        actions.add(service -> service.parseRule(TriageHarness.PROJECT, RULE, null));
        actions.add(service -> service.testSingleRule(TriageHarness.PROJECT, RULE));
        actions.add(service -> service.testSingleRuleById(TriageHarness.PROJECT, "r1"));
        actions.add(service -> service.explainConclusion(TriageHarness.PROJECT,
                new ExplainRequest("a", "b", "c", null), null));
        actions.add(service -> service.whyNotFired(TriageHarness.PROJECT, "r1", new WhyNotRequest("a"), null));
        return actions;
    }

    @Test
    void aRefusedCallNeverFetchesTheOntologyOnAnyAdmittedPath() throws Exception {
        for (Action action : everyAdmittedPath()) {
            TriageHarness harness = new TriageHarness(TriageHarness.rule("r1", "Child", RULE));
            Mockito.clearInvocations(harness.client);
            ExecutionGate gate = fullGate();
            ReflectionTestUtils.setField(harness.service, "executionGate", gate);
            CountDownLatch release = fill(gate);
            OwnerContext.set(OWNER);

            SwrlBusyException busy = assertThrows(SwrlBusyException.class, () -> action.run(harness.service));

            assertEquals(ExecutionGate.QUEUE_FULL, busy.getReason());
            Mockito.verifyNoInteractions(harness.client);
            release.countDown();
            OwnerContext.clear();
            assertDrained(gate);
        }
    }

    @Test
    void inferringFromTextIsAdmittedBeforeItParsesAnything() throws Exception {
        TriageHarness harness = new TriageHarness();
        ExecutionGate gate = fullGate();
        ReflectionTestUtils.setField(harness.service, "executionGate", gate);
        CountDownLatch release = fill(gate);
        OwnerContext.set(OWNER);

        assertThrows(SwrlBusyException.class, () -> harness.service.inferOntologyRules("not even an ontology"));

        release.countDown();
        assertDrained(gate);
    }

    @Test
    void anAcceptedCallFetchesOnceInsideTheGate() throws Exception {
        warmUp();
        TriageHarness harness = new TriageHarness();
        ExecutionGate gate = new ExecutionGate(1, 1, 1, 1, 10000);
        ReflectionTestUtils.setField(harness.service, "executionGate", gate);
        List<String> threads = new ArrayList<>();
        Mockito.when(harness.client.fetchOntology(Mockito.anyString())).thenAnswer(invocation -> {
            threads.add(Thread.currentThread().getName());
            return harness.fresh();
        });

        assertTrue(harness.service.executeSqwrlQuery(TriageHarness.PROJECT, QUERY, "q", null).isSuccess());

        assertEquals(1, threads.size());
        assertTrue(threads.get(0).startsWith("swrl-exec-"), threads.get(0));
        assertDrained(gate);
    }

    @Test
    void theSlotIsReleasedAfterSuccessFailureTooLargeAndTimeout() throws Exception {
        TriageHarness harness = new TriageHarness(TriageHarness.rule("r1", "Child", RULE));
        ExecutionGate gate = new ExecutionGate(1, 1, 1, 1, 10000);
        ReflectionTestUtils.setField(harness.service, "executionGate", gate);
        OwnerContext.set(OWNER);

        assertTrue(harness.service.executeRules(TriageHarness.PROJECT, null).isSuccess());
        assertDrained(gate);

        Mockito.doThrow(new org.semanticweb.owlapi.model.OWLOntologyCreationException("editor is down"))
                .when(harness.client).fetchOntologyWithStamp(Mockito.anyString(), Mockito.any());
        ExecutionResult failed = harness.service.executeRules(TriageHarness.PROJECT, null);
        assertFalse(failed.isSuccess());
        assertDrained(gate);

        Mockito.doThrow(new OntologyTooLargeException(1000))
                .when(harness.client).fetchOntologyWithStamp(Mockito.anyString(), Mockito.any());
        ExecutionResult large = harness.service.executeRules(TriageHarness.PROJECT, null);
        assertFalse(large.isSuccess());
        assertTrue(large.getErrorMessage().startsWith(TOO_LARGE), large.getErrorMessage());
        assertDrained(gate);

        ReflectionTestUtils.setField(harness.service, "executionTimeoutSeconds", 1);
        ReflectionTestUtils.setField(harness.service, "fetchAllowanceSeconds", 0);
        Mockito.doAnswer(invocation -> {
            Thread.sleep(5000);
            return null;
        }).when(harness.client).fetchOntologyWithStamp(Mockito.anyString(), Mockito.any());
        long begun = System.currentTimeMillis();
        ExecutionResult timedOut = harness.service.executeRules(TriageHarness.PROJECT, null);
        assertFalse(timedOut.isSuccess());
        assertEquals("timeout", timedOut.getCompletion());
        assertTrue(System.currentTimeMillis() - begun < 4000);
        assertDrained(gate);

        ReflectionTestUtils.setField(harness.service, "executionTimeoutSeconds", 120);
        Mockito.doAnswer(invocation -> new OntologyClientService.FetchedOntology(harness.fresh(), 1L, null))
                .when(harness.client).fetchOntologyWithStamp(Mockito.anyString(), Mockito.any());
        assertTrue(harness.service.executeRules(TriageHarness.PROJECT, null).isSuccess());
        assertDrained(gate);
    }

    @Test
    void aTooLargeOntologyGivesThePlainMessageOnQueryValidateAndTest() throws Exception {
        TriageHarness harness = new TriageHarness();
        ExecutionGate gate = new ExecutionGate(1, 1, 1, 1, 10000);
        ReflectionTestUtils.setField(harness.service, "executionGate", gate);
        Mockito.doThrow(new OntologyTooLargeException(150000)).when(harness.client).fetchOntology(Mockito.anyString());
        Mockito.doThrow(new OntologyTooLargeException(150000)).when(harness.client).fetchOntologyWithStamp(Mockito.anyString(), Mockito.any());

        String query = harness.service.executeSqwrlQuery(TriageHarness.PROJECT, QUERY, "q", null).getErrorMessage();
        String validate = harness.service.validateRule(TriageHarness.PROJECT, RULE).getErrorMessage();
        String test = harness.service.testSingleRule(TriageHarness.PROJECT, RULE).getErrorMessage();

        for (String message : List.of(query, validate, test)) {
            assertTrue(message.startsWith(TOO_LARGE), message);
            assertTrue(message.contains("Split it into smaller ontologies"), message);
            assertTrue(message.contains("150,000"), message);
        }
        assertDrained(gate);
    }

    @Test
    void concurrentCallsBeyondTheLimitAreRefusedQuicklyWithoutFetching() throws Exception {
        warmUp();
        TriageHarness harness = new TriageHarness();
        ExecutionGate gate = new ExecutionGate(1, 1, 10, 10, 120000);
        ReflectionTestUtils.setField(harness.service, "executionGate", gate);
        AtomicInteger fetches = new AtomicInteger();
        java.util.concurrent.ConcurrentLinkedQueue<org.semanticweb.owlapi.model.OWLOntology> ready = new java.util.concurrent.ConcurrentLinkedQueue<>();
        for (int i = 0; i < 6; i++) {
            ready.add(harness.fresh());
        }
        Mockito.doAnswer(invocation -> {
            fetches.incrementAndGet();
            Thread.sleep(1500);
            return ready.poll();
        }).when(harness.client).fetchOntology(Mockito.anyString());
        int callers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<long[]>> outcomes = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            outcomes.add(pool.submit(() -> {
                go.await();
                long begun = System.currentTimeMillis();
                try {
                    boolean ok = harness.service.executeSqwrlQuery(TriageHarness.PROJECT, QUERY, "c", null).isSuccess();
                    return new long[]{ok ? 1 : 0, System.currentTimeMillis() - begun};
                } catch (SwrlBusyException e) {
                    return new long[]{2, System.currentTimeMillis() - begun};
                }
            }));
        }
        go.countDown();
        int ok = 0;
        int busy = 0;
        long slowestRefusal = 0;
        for (Future<long[]> outcome : outcomes) {
            long[] value = outcome.get(30, TimeUnit.SECONDS);
            if (value[0] == 1) {
                ok++;
            } else if (value[0] == 2) {
                busy++;
                slowestRefusal = Math.max(slowestRefusal, value[1]);
            }
        }
        pool.shutdownNow();
        assertEquals(callers, ok + busy);
        assertTrue(busy >= 3, "busy " + busy);
        assertTrue(slowestRefusal < 1000, "slowest refusal " + slowestRefusal);
        assertEquals(ok, fetches.get());
        assertDrained(gate);
    }

    @Test
    void theListingPathsStayOutsideTheGate() throws Exception {
        TriageHarness harness = new TriageHarness(TriageHarness.rule("r1", "Child", RULE));
        ExecutionGate gate = fullGate();
        ReflectionTestUtils.setField(harness.service, "executionGate", gate);
        CountDownLatch release = fill(gate);
        OwnerContext.set(OWNER);

        assertEquals(1, harness.service.listEmbeddedRules(TriageHarness.PROJECT, null).size());
        assertTrue(harness.service.exportEnabledRulesAsOwl(TriageHarness.PROJECT, null).text().contains("Child"));

        release.countDown();
        assertDrained(gate);
    }
}
