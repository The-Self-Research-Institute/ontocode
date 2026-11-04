package self.research.ontology.plugins.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.common.PreparedOntology;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasonerServiceBoundsTest {

    private final ExecutorService callers = Executors.newCachedThreadPool();
    private ReasonerService service;
    private volatile OWLOntology blockedOntology;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        service = new ReasonerService() {
            @Override
            PreparedOntology prepareRules(OWLOntology input, ReasonerType type) {
                if (input == blockedOntology) {
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return new PreparedOntology(input, 0, 0, 0, null);
            }
        };
        ReflectionTestUtils.setField(service, "reasonerOperationTimeoutMs", 30_000L);
        ReflectionTestUtils.setField(service, "disposeGraceSeconds", 0L);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        callers.shutdownNow();
    }

    private static OWLOntology ontology() throws Exception {
        return OWLManager.createOWLOntologyManager().createOntology();
    }

    @Test
    void theRealizationPoolIsFixedSizeWithABoundedQueueAndRefusesWhenFull() {
        ThreadPoolExecutor pool = (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "realizationExecutor");

        assertEquals(4, pool.getCorePoolSize());
        assertEquals(4, pool.getMaximumPoolSize());
        assertEquals(50, pool.getQueue().remainingCapacity());
        assertTrue(pool.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy);
    }

    @Test
    void whenTheRealizationPoolAndQueueAreFullTheCallerGetsTheTooManyRequestsMessage() {
        ReflectionTestUtils.setField(service, "realizationWorkers", 1);
        ReflectionTestUtils.setField(service, "realizationQueue", 1);
        ReflectionTestUtils.setField(service, "PER_INDIVIDUAL_TYPE_TIMEOUT_MS", 100L);
        ReflectionTestUtils.setField(service, "justificationPoolSize", 2);
        ReflectionTestUtils.invokeMethod(service, "initJustificationExecutor");
        CountDownLatch stuck = new CountDownLatch(1);
        OWLReasoner reasoner = Mockito.mock(OWLReasoner.class);
        Mockito.when(reasoner.getTypes(Mockito.any(OWLNamedIndividual.class), Mockito.anyBoolean())).thenAnswer(invocation -> {
            stuck.await(10, TimeUnit.SECONDS);
            return null;
        });
        OWLNamedIndividual individual = Mockito.mock(OWLNamedIndividual.class);

        try {
            for (int i = 0; i < 2; i++) {
                Throwable thrown = assertThrows(Throwable.class,
                        () -> ReflectionTestUtils.invokeMethod(service, "getTypesWithTimeout", reasoner, individual));
                Throwable cause = thrown.getCause() != null ? thrown.getCause() : thrown;
                assertTrue(cause instanceof TimeoutException, String.valueOf(thrown));
            }
            RuntimeException refused = assertThrows(RuntimeException.class,
                    () -> ReflectionTestUtils.invokeMethod(service, "getTypesWithTimeout", reasoner, individual));
            assertEquals("Too many reasoning requests in progress — please try again shortly.", refused.getMessage());
        } finally {
            stuck.countDown();
        }
    }

    @Test
    void theReasonerCacheIsBoundedAndKeepsTheMostRecentlyUsed() throws Exception {
        ReflectionTestUtils.setField(service, "reasonerCacheMaxSize", 2);
        OWLOntology a = ontology();
        OWLOntology b = ontology();
        OWLOntology c = ontology();
        OWLReasoner firstA = service.getReasoner(a, ReasonerType.HERMIT);
        service.getReasoner(b, ReasonerType.HERMIT);
        Thread.sleep(5);
        assertSame(firstA, service.getReasoner(a, ReasonerType.HERMIT));
        Thread.sleep(5);

        service.getReasoner(c, ReasonerType.HERMIT);

        assertEquals(2, service.reasonerCacheSize());
        assertSame(firstA, service.getReasoner(a, ReasonerType.HERMIT));
        assertEquals(2, service.reasonerCacheSize());
    }

    @Test
    void anEntryOlderThanTheTimeToLiveIsDropped() throws Exception {
        ReflectionTestUtils.setField(service, "reasonerCacheTtlMinutes", 1L);
        OWLOntology a = ontology();
        OWLOntology b = ontology();
        OWLReasoner firstA = service.getReasoner(a, ReasonerType.HERMIT);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Long> lastUse = (java.util.Map<String, Long>) ReflectionTestUtils.getField(service, "reasonerLastUse");
        lastUse.replaceAll((key, value) -> System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5));

        service.getReasoner(b, ReasonerType.HERMIT);

        assertEquals(1, service.reasonerCacheSize());
        assertNotSame(firstA, service.getReasoner(a, ReasonerType.HERMIT));
    }

    @Test
    void aBuildStillRunningIsNeverEvictedEvenWhenTheCacheIsOverItsLimit() throws Exception {
        ReflectionTestUtils.setField(service, "reasonerCacheMaxSize", 1);
        OWLOntology slow = ontology();
        blockedOntology = slow;
        OWLOntology other = ontology();
        Future<OWLReasoner> building = callers.submit(() -> service.getReasoner(slow, ReasonerType.HERMIT));
        long end = System.currentTimeMillis() + 5000;
        while (service.reasonerCacheSize() < 1 && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }

        service.getReasoner(other, ReasonerType.HERMIT);

        assertEquals(2, service.reasonerCacheSize());
        release.countDown();
        OWLReasoner built = building.get(10, TimeUnit.SECONDS);
        assertSame(built, service.getReasoner(slow, ReasonerType.HERMIT));
    }
}
