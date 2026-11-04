package self.research.ontology.swrl.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssertedAxiomsServiceTest {

    private final OntologyClientService client = Mockito.mock(OntologyClientService.class);
    private final SwrlEngineService service = new SwrlEngineService();

    AssertedAxiomsServiceTest() throws Exception {
        ReflectionTestUtils.setField(service, "ontologyClient", client);
        ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 60);
        Mockito.when(client.maxAxioms()).thenReturn(1_000_000L);
        Mockito.when(client.fetchOntologyWithStamp(Mockito.anyString(), Mockito.any()))
                .thenAnswer(i -> new OntologyClientService.FetchedOntology(AssertedAxiomListerTest.bakery(), 1L, null));
    }

    @AfterEach
    void clear() {
        OwnerContext.clear();
        service.shutdownGate();
    }

    @Test
    void thePublicOntologyIsFetchedWithoutADraftUser() throws Exception {
        AssertedAxiomLister.Page page = service.listAssertedAxioms("p1", null, null, null, 0, 50);

        assertEquals(7, page.total());
        Mockito.verify(client).fetchOntologyWithStamp("p1", null);
    }

    @Test
    void aDraftIsReadForTheNamedUserOnly() throws Exception {
        service.listAssertedAxioms("p1", "u1", null, null, 0, 50);

        Mockito.verify(client).fetchOntologyWithStamp("p1", "u1");
        Mockito.verify(client, Mockito.never()).fetchOntologyWithStamp("p1", null);
    }

    @Test
    void anUnchangedOntologyIsFetchedOnceAcrossPagesAndAChangeFetchesAgain() throws Exception {
        Mockito.when(client.currentStamp("p1", null)).thenReturn("r:1");

        service.listAssertedAxioms("p1", null, null, null, 0, 3);
        service.listAssertedAxioms("p1", null, "rye1", null, 0, 3);
        service.listAssertedAxioms("p1", null, null, "ClassAssertion", 1, 3);
        Mockito.verify(client, Mockito.times(1)).fetchOntologyWithStamp("p1", null);

        Mockito.when(client.currentStamp("p1", null)).thenReturn("r:2");
        service.listAssertedAxioms("p1", null, null, null, 0, 3);
        Mockito.verify(client, Mockito.times(2)).fetchOntologyWithStamp("p1", null);
    }

    @Test
    void aDraftAndThePublicOntologyDoNotShareACacheEntry() throws Exception {
        Mockito.when(client.currentStamp("p1", null)).thenReturn("r:1");
        Mockito.when(client.currentStamp("p1", "u1")).thenReturn("d:1");

        service.listAssertedAxioms("p1", null, null, null, 0, 3);
        service.listAssertedAxioms("p1", "u1", null, null, 0, 3);

        Mockito.verify(client).fetchOntologyWithStamp("p1", null);
        Mockito.verify(client).fetchOntologyWithStamp("p1", "u1");
    }

    @Test
    void withoutAStampEveryCallFetchesFresh() throws Exception {
        service.listAssertedAxioms("p1", null, null, null, 0, 3);
        service.listAssertedAxioms("p1", null, null, null, 0, 3);

        Mockito.verify(client, Mockito.times(2)).fetchOntologyWithStamp("p1", null);
    }

    @Test
    void anUnknownTypeIsRejectedBeforeAnythingIsFetched() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.listAssertedAxioms("p1", null, null, "Bogus", 0, 3));

        Mockito.verify(client, Mockito.never()).fetchOntologyWithStamp(Mockito.anyString(), Mockito.any());
    }

    @Test
    void aBusyGateRefusesWithTheSameBusyAnswerAsTheRuleEngine() throws Exception {
        ReflectionTestUtils.setField(service, "validationWorkers", 1);
        ReflectionTestUtils.setField(service, "validationQueue", 1);
        ReflectionTestUtils.setField(service, "validationMaxWaitMs", 200L);
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                service.validationGate().call("someone-else", () -> {
                    inside.countDown();
                    release.await();
                    return null;
                }, 30);
            } catch (Exception ignored) {
            }
        });
        holder.start();
        inside.await();

        SwrlBusyException busy = assertThrows(SwrlBusyException.class,
                () -> service.listAssertedAxioms("p1", null, null, null, 0, 3));

        assertEquals(ExecutionGate.WAIT_TIMEOUT, busy.getReason());
        release.countDown();
        holder.join();
    }
}
