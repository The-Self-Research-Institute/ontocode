package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.common.admission.ReservationLedger;
import self.research.ontology.common.admission.Estimates;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReasonerServiceLeaseTest {

    private ReasonerService service;
    private OWLOntology ontology;
    private ReservationLedger ledger;

    @BeforeEach
    void setUp() throws Exception {
        service = new ReasonerService();
        ReflectionTestUtils.setField(service, "reasonerOperationTimeoutMs", 30_000L);
        ledger = new FakeMemorySignals().ledger();
        service.setCapacityLedger(ledger);
        service.useAdmission(new HeavyWorkAdmission(ledger, new Estimates()));
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        ontology = manager.createOntology(IRI.create("http://example.org/lease"));
        OWLClass animal = df.getOWLClass(IRI.create("http://example.org/lease#Animal"));
        OWLClass dog = df.getOWLClass(IRI.create("http://example.org/lease#Dog"));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(dog, animal));
    }

    @AfterEach
    void tearDown() {
        service.clearCache();
    }

    private long reasonerBytes() {
        return ledger.capacityReport().cacheAccounts().getOrDefault("reasoners", 0L);
    }

    @Test
    void manyThreadsNeverSeeADisposedReasonerAndItIsDisposedExactlyOnce() throws Exception {
        OWLReasoner shared = mock(OWLReasoner.class);
        AtomicBoolean disposed = new AtomicBoolean();
        doAnswer(inv -> {
            disposed.set(true);
            return null;
        }).when(shared).dispose();
        ReasonerService.Entry entry = service.installEntryForTest(ontology, ReasonerType.HERMIT);
        entry.future.complete(shared);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger sawSharedLeased = new AtomicInteger();
        AtomicInteger violations = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < 200; i++) {
                    try (ReasonerLease lease = service.lease(ontology, ReasonerType.HERMIT)) {
                        if (lease.reasoner() == shared) {
                            sawSharedLeased.incrementAndGet();
                            if (disposed.get()) {
                                violations.incrementAndGet();
                            }
                            Thread.yield();
                            if (disposed.get()) {
                                violations.incrementAndGet();
                            }
                        }
                    } catch (BusyException ignored) {
                    }
                }
                return null;
            }));
        }
        go.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (sawSharedLeased.get() < 50 && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        service.disposeReasoner(ontology, ReasonerType.HERMIT);
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(0, violations.get());
        assertTrue(sawSharedLeased.get() > 0);
        verify(shared, times(1)).dispose();
        assertEquals(0, entry.borrowerCount());
    }

    @Test
    void evictionWhileLeasedDisposesOnlyAfterTheLastRelease() throws Exception {
        OWLReasoner shared = mock(OWLReasoner.class);
        ReasonerService.Entry entry = service.installEntryForTest(ontology, ReasonerType.HERMIT);
        entry.future.complete(shared);

        ReasonerLease lease = service.lease(ontology, ReasonerType.HERMIT);
        ReasonerLease handedOff = lease.handOff();
        service.disposeReasoner(ontology, ReasonerType.HERMIT);
        verify(shared, times(0)).dispose();
        assertEquals(0, service.cachedEntryCount());

        lease.close();
        verify(shared, times(0)).dispose();
        handedOff.close();
        verify(shared, times(1)).dispose();
        lease.close();
        handedOff.close();
        verify(shared, times(1)).dispose();
    }

    @Test
    void ledgerAccountingReturnsToZeroAfterBuildUseAndEviction() throws Exception {
        try (ReasonerLease lease = service.lease(ontology, ReasonerType.HERMIT)) {
            assertTrue(lease.reasoner().isConsistent());
            assertTrue(reasonerBytes() > 0);
            assertEquals(0, ledger.reservedBytes());
            service.disposeReasoner(ontology, ReasonerType.HERMIT);
            assertTrue(reasonerBytes() > 0);
        }
        assertEquals(0, reasonerBytes());
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void aFailedBuildReleasesItsReservation() {
        OWLOntology broken = mock(OWLOntology.class);
        when(broken.getAxiomCount()).thenReturn(10);
        when(broken.getOntologyID()).thenReturn(new OWLOntologyID());

        assertThrows(RuntimeException.class, () -> service.lease(broken, ReasonerType.ELK));

        assertEquals(0, ledger.reservedBytes());
        assertEquals(0, reasonerBytes());
        assertEquals(0, service.cachedEntryCount());
    }

    @Test
    void oneShotLeasesAreNeverCachedAndAreDisposedOnClose() throws Exception {
        for (int i = 0; i < 8; i++) {
            try (ReasonerLease lease = service.leaseClassHierarchy(ontology, ReasonerType.HERMIT, true)) {
                assertTrue(lease.reasoner().isConsistent());
                assertEquals(0, service.cachedEntryCount());
            }
        }
        assertEquals(0, reasonerBytes());
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void oneShotCopiesDoNotFillTheCacheSlots() throws Exception {
        try (ReasonerLease keep = service.lease(ontology, ReasonerType.HERMIT)) {
            for (int i = 0; i < 6; i++) {
                try (ReasonerLease copy = service.leaseClassHierarchy(copyOf(ontology), ReasonerType.HERMIT, true)) {
                    assertNotEquals(keep.reasoner(), copy.reasoner());
                }
            }
            assertEquals(1, service.cachedEntryCount());
        }
    }

    private static OWLOntology copyOf(OWLOntology source) throws Exception {
        return OWLManager.createOWLOntologyManager().copyOntology(source,
                org.semanticweb.owlapi.model.parameters.OntologyCopy.DEEP);
    }

    @Test
    void aSecondRequestOnTheSameReasonerWaitsAndThenIsRefusedWithAPlainMessage() throws Exception {
        ReflectionTestUtils.setField(service, "leaseWaitMs", 150L);
        OWLReasoner shared = mock(OWLReasoner.class);
        service.installEntryForTest(ontology, ReasonerType.HERMIT).future.complete(shared);

        ReasonerLease first = service.lease(ontology, ReasonerType.HERMIT);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            BusyException busy = (BusyException) assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> other.submit(() -> service.lease(ontology, ReasonerType.HERMIT)).get(10, TimeUnit.SECONDS))
                    .getCause();
            assertTrue(busy.getMessage().contains("try again"));
            Future<ReasonerLease> later = other.submit(() -> service.lease(ontology, ReasonerType.HERMIT));
            ReflectionTestUtils.setField(service, "leaseWaitMs", 5_000L);
            first.close();
            later.get(10, TimeUnit.SECONDS).close();
        } finally {
            other.shutdownNow();
        }
        assertEquals(0, service.cachedEntryForTest(ontology, ReasonerType.HERMIT).borrowerCount());
    }

    @Test
    void sameThreadMayLeaseTheSameReasonerAgain() {
        OWLReasoner shared = mock(OWLReasoner.class);
        service.installEntryForTest(ontology, ReasonerType.HERMIT).future.complete(shared);
        try (ReasonerLease outer = service.lease(ontology, ReasonerType.HERMIT);
                ReasonerLease inner = service.lease(ontology, ReasonerType.HERMIT)) {
            assertEquals(shared, inner.reasoner());
            assertEquals(shared, outer.reasoner());
        }
    }

    @Test
    void keysOfDifferentOntologiesNeverCollideEvenWithTheSameHash() throws Exception {
        OWLOntology other = OWLManager.createOWLOntologyManager().createOntology(IRI.create("http://example.org/lease"));
        ReasonerService.Key a = new ReasonerService.Key(ontology, ReasonerType.HERMIT);
        ReasonerService.Key sameOntology = new ReasonerService.Key(ontology, ReasonerType.HERMIT);
        ReasonerService.Key b = new ReasonerService.Key(other, ReasonerType.HERMIT);
        ReasonerService.Key otherType = new ReasonerService.Key(ontology, ReasonerType.ELK);
        assertEquals(a, sameOntology);
        assertNotEquals(a, b);
        assertNotEquals(a, otherType);
        assertFalse(a.equals(null));
    }

    @Test
    void anAbandonedHandOffKeepsTheReasonerAliveUntilItEnds() {
        OWLReasoner shared = mock(OWLReasoner.class);
        service.installEntryForTest(ontology, ReasonerType.HERMIT).future.complete(shared);
        ReasonerLease lease = service.lease(ontology, ReasonerType.HERMIT);
        ReasonerLease abandoned = lease.handOff();
        lease.retire();
        lease.close();
        verify(shared, times(0)).dispose();
        abandoned.close();
        verify(shared, times(1)).dispose();
        verify(shared, times(0)).interrupt();
    }
}
