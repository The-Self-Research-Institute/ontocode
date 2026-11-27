package self.research.ontology.owlEditor.service;

import org.apache.http.impl.client.CloseableHttpClient;
import org.eclipse.rdf4j.repository.Repository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PerFileClientsTest {

    @Test
    void aRepositoryThatIsThrownAwayHasItsConnectionPoolClosedAtOnce() throws Exception {
        PerFileClients clients = new PerFileClients();
        Repository repository = mock(Repository.class);
        CloseableHttpClient pool = mock(CloseableHttpClient.class);
        clients.register(repository, pool);

        clients.closeNow(repository);

        verify(repository).shutDown();
        verify(pool).close();
        assertEquals(0, clients.open());
    }

    @Test
    void anEvictedRepositoryIsClosedOnlyAfterTheGracePeriodSoRunningRequestsFinish() throws Exception {
        List<Runnable> scheduled = new ArrayList<>();
        List<Duration> delays = new ArrayList<>();
        PerFileClients clients = new PerFileClients(grace -> {
            delays.add(grace);
            return scheduled::add;
        });
        Repository repository = mock(Repository.class);
        CloseableHttpClient pool = mock(CloseableHttpClient.class);
        clients.register(repository, pool);

        clients.closeAfterGrace(repository);

        verify(pool, never()).close();
        assertEquals(List.of(PerFileClients.GRACE), delays);
        scheduled.forEach(Runnable::run);
        verify(repository).shutDown();
        verify(pool).close();
        assertEquals(0, clients.open());
    }

    @Test
    void nothingToCloseIsHarmless() {
        PerFileClients clients = new PerFileClients();
        clients.closeNow(null);
        clients.closeAfterGrace(null);
        clients.closeNow(mock(Repository.class));
        assertEquals(0, clients.open());
    }
}
