package self.research.ontology.owlEditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Silent background Fuseki sync for OWLAPI-first desktop.
 * Editor open and mutations use OWLAPI only; this catches Fuseki up without blocking the UI.
 */
@Service
@ConditionalOnProperty(name = "ontocode.desktop.owlapi-first", havingValue = "true")
public class DesktopFusekiSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(DesktopFusekiSyncScheduler.class);

    private static final long OPEN_DELAY_MS = 3_000;
    private static final long MUTATION_DEBOUNCE_MS = 20_000;
    private static final long RETRY_DELAY_MS = 45_000;
    private static final int MAX_RETRIES = 4;

    private final ProjectImportService projectImportService;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fuseki-bg-sync");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> retryCounts = new ConcurrentHashMap<>();

    public DesktopFusekiSyncScheduler(@Lazy ProjectImportService projectImportService) {
        this.projectImportService = projectImportService;
    }

    /** After ontology open / OWLAPI warm — short delay, no debounce stacking. */
    public void scheduleAfterOpen(String projectId) {
        schedule(projectId, OPEN_DELAY_MS, false);
    }

    /** After OWLAPI mutation — debounce bursts of edits into one upload. */
    public void scheduleAfterMutation(String projectId) {
        schedule(projectId, MUTATION_DEBOUNCE_MS, true);
    }

    private void schedule(String projectId, long delayMs, boolean debounce) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        if (!projectImportService.isFusekiSyncPending(projectId)) {
            return;
        }
        ScheduledFuture<?> existing = scheduled.get(projectId);
        if (existing != null && !existing.isDone()) {
            if (!debounce) {
                return;
            }
            existing.cancel(false);
        }
        ScheduledFuture<?> future = executor.schedule(() -> runSync(projectId), delayMs, TimeUnit.MILLISECONDS);
        scheduled.put(projectId, future);
        log.debug("[FusekiBg] Scheduled sync for {} in {}ms (debounce={})", projectId, delayMs, debounce);
    }

    private void runSync(String projectId) {
        if (inFlight.putIfAbsent(projectId, Boolean.TRUE) != null) {
            scheduleAfterMutation(projectId);
            return;
        }
        try {
            if (!projectImportService.isFusekiSyncPending(projectId)) {
                retryCounts.remove(projectId);
                return;
            }
            log.info("[FusekiBg] Silent sync starting for {}", projectId);
            Map<String, Object> result = projectImportService.syncProjectToFuseki(projectId);
            if (Boolean.TRUE.equals(result.get("synced"))) {
                retryCounts.remove(projectId);
                log.info("[FusekiBg] Silent sync completed for {}", projectId);
                return;
            }
            String error = result.get("error") != null ? result.get("error").toString() : "unknown";
            log.debug("[FusekiBg] Silent sync deferred for {}: {}", projectId, error);
            scheduleRetry(projectId);
        } catch (Exception e) {
            log.warn("[FusekiBg] Silent sync failed for {}: {}", projectId, e.getMessage());
            scheduleRetry(projectId);
        } finally {
            inFlight.remove(projectId);
        }
    }

    private void scheduleRetry(String projectId) {
        AtomicInteger attempts = retryCounts.computeIfAbsent(projectId, id -> new AtomicInteger(0));
        if (attempts.incrementAndGet() > MAX_RETRIES) {
            log.info("[FusekiBg] Giving up silent sync for {} after {} retries (Fuseki may be offline)", projectId, MAX_RETRIES);
            return;
        }
        ScheduledFuture<?> future = executor.schedule(() -> runSync(projectId), RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
        scheduled.put(projectId, future);
    }
}
