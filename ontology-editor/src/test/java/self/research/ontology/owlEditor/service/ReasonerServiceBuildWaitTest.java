package self.research.ontology.owlEditor.service;

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

    @Test
    void timedOutBuildIsDisposedOnceItFinishes() throws Exception {
        ReasonerService service = new ReasonerService();
        ReflectionTestUtils.setField(service, "reasonerOperationTimeoutMs", 50L);
        OWLOntology ontology = OWLManager.createOWLOntologyManager().createOntology();
        CompletableFuture<OWLReasoner> inFlight = service.installEntryForTest(ontology, ReasonerType.HERMIT).future;

        assertThrows(RuntimeException.class, () -> service.lease(ontology, ReasonerType.HERMIT));

        OWLReasoner lateReasoner = mock(OWLReasoner.class);
        inFlight.complete(lateReasoner);
        verify(lateReasoner, timeout(5_000)).dispose();
    }

    @Test
    void interruptedWaitKeepsTheBuildAndTheInterruptFlag() throws Exception {
        ReasonerService service = new ReasonerService();
        ReflectionTestUtils.setField(service, "reasonerOperationTimeoutMs", 5_000L);
        OWLOntology ontology = OWLManager.createOWLOntologyManager().createOntology();
        ReasonerService.Entry installed = service.installEntryForTest(ontology, ReasonerType.HERMIT);

        Thread.currentThread().interrupt();
        assertThrows(RuntimeException.class, () -> service.lease(ontology, ReasonerType.HERMIT));

        assertTrue(Thread.currentThread().isInterrupted());
        assertSame(installed, service.cachedEntryForTest(ontology, ReasonerType.HERMIT));
    }
}
