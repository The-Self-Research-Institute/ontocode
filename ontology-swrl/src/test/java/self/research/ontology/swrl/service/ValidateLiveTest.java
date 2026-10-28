package self.research.ontology.swrl.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.swrl.model.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidateLiveTest {

    private static final String RULE = "Patient(?p) ^ hasAge(?p, ?a) -> Child(?p)";
    private static final String NEW_CLASS_RULE = "Patient(?p) ^ hasAge(?p, ?a) -> Zed(?p)";
    private static final String UNBOUND_BUILTIN = "Patient(?p) ^ swrlb:greaterThan(?x, 18) -> Child(?p)";

    private final ExecutorService blockers = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        OwnerContext.clear();
        blockers.shutdownNow();
    }

    private static OWLOntology withZed() throws Exception {
        OWLOntology ontology = TriageHarness.bare();
        ontology.getOWLOntologyManager().addAxiom(ontology, OWLManager.getOWLDataFactory().getOWLDeclarationAxiom(
                OWLManager.getOWLDataFactory().getOWLClass(IRI.create("http://example.org/triage#Zed"))));
        return ontology;
    }

    private static TriageHarness stamped(AtomicLong stamp) throws Exception {
        TriageHarness harness = new TriageHarness();
        Mockito.when(harness.client.currentStamp(Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> "r:" + stamp.get());
        Mockito.when(harness.client.maxAxioms()).thenReturn(1_000_000L);
        return harness;
    }

    private void fillGate(ExecutionGate gate) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            String owner = "filler-" + i;
            blockers.submit(() -> gate.call(owner, () -> {
                started.countDown();
                new CountDownLatch(1).await();
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
    }

    @Test
    void validatingDoesNotRunInferenceSoAnUnboundBuiltinPassesWhileSavingStillCatchesIt() throws Exception {
        TriageHarness harness = new TriageHarness();

        ValidationResult ok = harness.service.validateRule(TriageHarness.PROJECT, RULE);
        ValidationResult unbound = harness.service.validateRule(TriageHarness.PROJECT, UNBOUND_BUILTIN);

        assertTrue(ok.isValid(), ok.getErrorMessage());
        assertTrue(unbound.isValid(), unbound.getErrorMessage());
        assertFalse(harness.service.parseRule(TriageHarness.PROJECT, UNBOUND_BUILTIN, null).valid());
    }

    @Test
    void savingStillRunsInferenceAndCatchesAnUnboundBuiltin() throws Exception {
        TriageHarness harness = new TriageHarness();

        assertTrue(harness.service.parseRule(TriageHarness.PROJECT, RULE, null).valid());
        assertFalse(harness.service.parseRule(TriageHarness.PROJECT, UNBOUND_BUILTIN, null).valid());
    }

    @Test
    void anUnchangedStampReusesTheParsedOntologyAndLeavesNoTempRuleBehind() throws Exception {
        AtomicLong stamp = new AtomicLong(1);
        TriageHarness harness = stamped(stamp);
        List<OWLOntology> fetched = new ArrayList<>();
        Mockito.when(harness.client.fetchOntology(Mockito.anyString())).thenAnswer(invocation -> {
            OWLOntology ontology = TriageHarness.bare();
            fetched.add(ontology);
            return ontology;
        });

        for (int i = 0; i < 5; i++) {
            assertTrue(harness.service.validateRule(TriageHarness.PROJECT, RULE).isValid());
        }

        assertEquals(1, fetched.size());
        assertEquals(0, fetched.get(0).getAxioms(AxiomType.SWRL_RULE).size());
    }

    @Test
    void aChangedStampNeverValidatesAgainstTheOldData() throws Exception {
        AtomicLong stamp = new AtomicLong(1);
        TriageHarness harness = stamped(stamp);
        List<OWLOntology> served = new ArrayList<>();
        served.add(TriageHarness.bare());
        served.add(withZed());
        List<Integer> calls = new ArrayList<>();
        Mockito.when(harness.client.fetchOntology(Mockito.anyString())).thenAnswer(invocation -> {
            calls.add(1);
            return served.get(Math.min(calls.size(), served.size()) - 1);
        });

        ValidationResult before = harness.service.validateRule(TriageHarness.PROJECT, NEW_CLASS_RULE);
        ValidationResult cached = harness.service.validateRule(TriageHarness.PROJECT, NEW_CLASS_RULE);
        assertEquals(1, calls.size());
        stamp.set(2);
        ValidationResult after = harness.service.validateRule(TriageHarness.PROJECT, NEW_CLASS_RULE);

        assertFalse(before.isValid());
        assertFalse(cached.isValid());
        assertTrue(after.isValid(), after.getErrorMessage());
        assertEquals(2, calls.size());
    }

    @Test
    void noStampMeansNoCaching() throws Exception {
        TriageHarness harness = new TriageHarness();

        harness.service.validateRule(TriageHarness.PROJECT, RULE);
        harness.service.validateRule(TriageHarness.PROJECT, RULE);

        Mockito.verify(harness.client, Mockito.times(2)).fetchOntology(Mockito.anyString());
    }

    @Test
    void anotherOwnerNeverReusesSomeoneElsesEntry() throws Exception {
        AtomicLong stamp = new AtomicLong(1);
        TriageHarness harness = stamped(stamp);
        Mockito.when(harness.client.fetchOntology(Mockito.anyString())).thenAnswer(invocation -> TriageHarness.bare());

        OwnerContext.set("ann");
        harness.service.validateRule(TriageHarness.PROJECT, RULE);
        OwnerContext.set("bob");
        harness.service.validateRule(TriageHarness.PROJECT, RULE);

        Mockito.verify(harness.client, Mockito.times(2)).fetchOntology(Mockito.anyString());
    }

    @Test
    void aRunningExecuteDoesNotRefuseTyping() throws Exception {
        TriageHarness harness = new TriageHarness();
        ExecutionGate busy = new ExecutionGate(1, 1, 5, 5, 10000);
        ReflectionTestUtils.setField(harness.service, "executionGate", busy);
        fillGate(busy);
        OwnerContext.set("typist");

        assertTrue(harness.service.validateRule(TriageHarness.PROJECT, RULE).isValid());
    }

    @Test
    void typingIsStillRefusedWhenTheValidationGateItselfIsFull() throws Exception {
        TriageHarness harness = new TriageHarness();
        ExecutionGate full = new ExecutionGate(1, 1, 5, 5, 10000);
        ReflectionTestUtils.setField(harness.service, "validationGate", full);
        fillGate(full);
        Mockito.clearInvocations(harness.client);
        OwnerContext.set("typist");

        SwrlBusyException busy = assertThrows(SwrlBusyException.class,
                () -> harness.service.validateRule(TriageHarness.PROJECT, RULE));

        assertEquals(ExecutionGate.QUEUE_FULL, busy.getReason());
        Mockito.verifyNoInteractions(harness.client);
    }

    @Test
    void theCacheHoldsAtMostThreeEntriesAndNeverMoreThanTheAxiomBudget() throws Exception {
        AtomicLong now = new AtomicLong(0);
        ValidationCache cache = new ValidationCache(3, 60_000, now::get);
        long axioms = TriageHarness.bare().getAxiomCount();
        for (int i = 0; i < 6; i++) {
            cache.put(new ValidationCache.Key("p" + i, null, "o"), "r:1", TriageHarness.bare(), null, axioms * 10);
        }
        assertEquals(3, cache.size());

        cache.put(new ValidationCache.Key("tight", null, "o"), "r:1", TriageHarness.bare(), null, axioms * 2);
        assertEquals(2, cache.size());
        assertTrue(cache.totalAxioms() <= axioms * 2);

        cache.put(new ValidationCache.Key("huge", null, "o"), "r:1", TriageHarness.bare(), null, axioms - 1);
        assertTrue(cache.totalAxioms() <= axioms * 2);
        assertNull(cache.get(new ValidationCache.Key("huge", null, "o"), "r:1"));
    }

    @Test
    void anEntryExpiresAfterTheTtlAndOnAStampMismatch() throws Exception {
        AtomicLong now = new AtomicLong(0);
        ValidationCache cache = new ValidationCache(3, 60_000, now::get);
        ValidationCache.Key key = new ValidationCache.Key("p", null, "o");
        cache.put(key, "r:1", TriageHarness.bare(), null, Long.MAX_VALUE);

        assertNotNull(cache.get(key, "r:1"));
        assertNull(cache.get(key, "r:2"));
        assertEquals(0, cache.size());

        cache.put(key, "r:1", TriageHarness.bare(), null, Long.MAX_VALUE);
        now.set(60_000);
        assertNull(cache.get(key, "r:1"));
    }
}
