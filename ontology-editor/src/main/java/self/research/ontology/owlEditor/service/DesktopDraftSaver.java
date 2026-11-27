package self.research.ontology.owlEditor.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.config.FastOpenCondition;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
@Conditional(FastOpenCondition.class)
public class DesktopDraftSaver {

    private static final Logger log = LoggerFactory.getLogger(DesktopDraftSaver.class);

    static final long FIRST_RETRY_MILLIS = 5_000;
    static final long MAX_RETRY_MILLIS = 120_000;

    @Nullable
    private final DesktopOntologyLoader loader;
    private final ProjectOntologyCache cache;
    private final long firstRetryMillis;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> failures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> retries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "draft-save-retry");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public DesktopDraftSaver(@Nullable DesktopOntologyLoader loader, ProjectOntologyCache cache) {
        this(loader, cache, FIRST_RETRY_MILLIS);
    }

    DesktopDraftSaver(@Nullable DesktopOntologyLoader loader, ProjectOntologyCache cache, long firstRetryMillis) {
        this.loader = loader;
        this.cache = cache;
        this.firstRetryMillis = firstRetryMillis;
    }

    public void save(String projectId) {
        if (!write(projectId)) {
            scheduleRetry(projectId, firstRetryMillis);
        }
    }

    public void flush(String projectId) throws IOException {
        if (failures.containsKey(projectId) && !write(projectId)) {
            throw new IOException("Your latest changes could not be written to disk yet: "
                    + failures.getOrDefault(projectId, "unknown error"));
        }
    }

    public void forget(String projectId) {
        failures.remove(projectId);
        ScheduledFuture<?> retry = retries.remove(projectId);
        if (retry != null) {
            retry.cancel(false);
        }
    }

    public Optional<String> failure(String projectId) {
        return Optional.ofNullable(failures.get(projectId));
    }

    private boolean write(String projectId) {
        if (loader == null) {
            return true;
        }
        synchronized (locks.computeIfAbsent(projectId, id -> new Object())) {
            try {
                loader.persistToDisk(projectId);
                if (failures.remove(projectId) != null) {
                    log.info("[DraftSave] Unsaved changes of {} are written to disk again", projectId);
                }
                return true;
            } catch (IOException | RuntimeException e) {
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                failures.put(projectId, reason);
                log.warn("[DraftSave] Could not write the unsaved changes of {} to disk; they stay in memory and "
                        + "saving is retried: {}", projectId, reason);
                return false;
            }
        }
    }

    private void scheduleRetry(String projectId, long delayMillis) {
        retries.compute(projectId, (id, pending) -> pending != null && !pending.isDone() ? pending
                : retryExecutor.schedule(() -> retry(projectId, delayMillis), delayMillis, TimeUnit.MILLISECONDS));
    }

    private void retry(String projectId, long delayMillis) {
        retries.remove(projectId);
        if (!failures.containsKey(projectId)) {
            return;
        }
        if (!cache.has(projectId)) {
            log.warn("[DraftSave] Stopped retrying the save of {}: its ontology is no longer open", projectId);
            failures.remove(projectId);
            return;
        }
        if (!write(projectId)) {
            scheduleRetry(projectId, Math.min(delayMillis * 2, MAX_RETRY_MILLIS));
        }
    }

    @PreDestroy
    void stop() {
        retryExecutor.shutdownNow();
    }
}
