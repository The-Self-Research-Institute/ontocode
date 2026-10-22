package self.research.ontology.owlEditor.service;

import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class ReasonerServiceBuildWaitTest {

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @SuppressWarnings("unchecked")
    private static Cache<String, CompletableFuture<OWLReasoner>> reasonerCache(ReasonerService service) {
        return (Cache<String, CompletableFuture<OWLReasoner>>) ReflectionTestUtils.getField(service, "reasonerCache");
    }

    private static String cacheKey(OWLOntology ontology, ReasonerType type) {
        return System.identityHashCode(ontology) + "-" + type.name();
    }

    @Test
    void timedOutBuildIsDisposedOnceItFinishes() throws Exception {
        ReasonerService service = new ReasonerService();
        ReflectionTestUtils.setField(service, "reasonerOperationTimeoutMs", 50L);
        OWLOntology ontology = OWLManager.createOWLOntologyManager().createOntology();
        CompletableFuture<OWLReasoner> inFlight = new CompletableFuture<>();
        reasonerCache(service).put(cacheKey(ontology, ReasonerType.HERMIT), inFlight);

        assertThrows(RuntimeException.class, () -> service.getReasoner(ontology, ReasonerType.HERMIT));

        OWLReasoner lateReasoner = mock(OWLReasoner.class);
        inFlight.complete(lateReasoner);
        verify(lateReasoner, timeout(5_000)).dispose();
    }

    @Test
    void interruptedWaitKeepsTheBuildAndTheInterruptFlag() throws Exception {
        ReasonerService service = new ReasonerService();
        ReflectionTestUtils.setField(service, "reasonerOperationTimeoutMs", 5_000L);
        OWLOntology ontology = OWLManager.createOWLOntologyManager().createOntology();
        CompletableFuture<OWLReasoner> inFlight = new CompletableFuture<>();
        String key = cacheKey(ontology, ReasonerType.HERMIT);
        reasonerCache(service).put(key, inFlight);

        Thread.currentThread().interrupt();
        assertThrows(RuntimeException.class, () -> service.getReasoner(ontology, ReasonerType.HERMIT));

        assertTrue(Thread.currentThread().isInterrupted());
        assertSame(inFlight, reasonerCache(service).getIfPresent(key));
    }
}
