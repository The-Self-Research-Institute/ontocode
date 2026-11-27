package self.research.ontology.owlEditor.service;

import org.apache.http.impl.client.CloseableHttpClient;
import org.eclipse.rdf4j.repository.Repository;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

final class PerFileClients {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PerFileClients.class);

    static final Duration GRACE = Duration.ofMinutes(2);

    private final Map<Repository, CloseableHttpClient> clients = new ConcurrentHashMap<>();
    private final Function<Duration, Executor> later;

    PerFileClients() {
        this(grace -> CompletableFuture.delayedExecutor(grace.toMillis(), TimeUnit.MILLISECONDS));
    }

    PerFileClients(Function<Duration, Executor> later) {
        this.later = later;
    }

    void register(Repository repository, CloseableHttpClient client) {
        clients.put(repository, client);
    }

    void closeNow(Repository repository) {
        if (repository == null) {
            return;
        }
        try {
            repository.shutDown();
        } catch (RuntimeException e) {
            log.debug("[PerFileDS] Repository did not shut down cleanly: {}", e.getMessage());
        }
        CloseableHttpClient client = clients.remove(repository);
        if (client != null) {
            try {
                client.close();
            } catch (IOException e) {
                log.debug("[PerFileDS] Connection pool did not close cleanly: {}", e.getMessage());
            }
        }
    }

    void closeAfterGrace(Repository repository) {
        if (repository != null) {
            later.apply(GRACE).execute(() -> closeNow(repository));
        }
    }

    int open() {
        return clients.size();
    }
}
