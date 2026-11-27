package self.research.ontology.owlEditor.service;

import jakarta.annotation.PreDestroy;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.config.FastOpenCondition;
import self.research.ontology.owlEditor.util.AtomicFiles;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

@Service
@Conditional(FastOpenCondition.class)
public class DesktopDraftCompactor {

    private static final Logger log = LoggerFactory.getLogger(DesktopDraftCompactor.class);

    static final long IDLE_MILLIS = 15_000;

    private final ProjectOntologyCache cache;
    private final DesktopEditJournal journal;
    private final StorageManager storage;
    private final ObjectProvider<DesktopFusekiSyncScheduler> syncScheduler;
    private final long idleMillis;
    private final ConcurrentHashMap<String, AtomicLong> editsStarted = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> editsRunning = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "draft-compaction");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public DesktopDraftCompactor(ProjectOntologyCache cache, DesktopEditJournal journal, StorageManager storage,
                                 ObjectProvider<DesktopFusekiSyncScheduler> syncScheduler) {
        this(cache, journal, storage, syncScheduler, IDLE_MILLIS);
    }

    DesktopDraftCompactor(ProjectOntologyCache cache, DesktopEditJournal journal, StorageManager storage,
                          ObjectProvider<DesktopFusekiSyncScheduler> syncScheduler, long idleMillis) {
        this.cache = cache;
        this.journal = journal;
        this.storage = storage;
        this.syncScheduler = syncScheduler;
        this.idleMillis = idleMillis;
    }

    public void editStarting(String projectId) {
        counter(editsStarted, projectId).incrementAndGet();
        counter(editsRunning, projectId).incrementAndGet();
    }

    public void editFinished(String projectId) {
        counter(editsRunning, projectId).decrementAndGet();
    }

    public void scheduleAfterEdit(String projectId) {
        scheduled.compute(projectId, (id, pending) -> {
            if (pending != null) {
                pending.cancel(false);
            }
            return executor.schedule(() -> compact(projectId), idleMillis, TimeUnit.MILLISECONDS);
        });
    }

    public void forget(String projectId) {
        ScheduledFuture<?> pending = scheduled.remove(projectId);
        if (pending != null) {
            pending.cancel(false);
        }
    }

    boolean compact(String projectId) {
        if (!journal.hasPending(projectId)) {
            return false;
        }
        if (counter(editsRunning, projectId).get() > 0) {
            scheduleAfterEdit(projectId);
            return false;
        }
        var entry = cache.get(projectId);
        if (entry.isEmpty()) {
            return false;
        }
        long startedBefore = counter(editsStarted, projectId).get();
        BooleanSupplier interrupted = () -> counter(editsStarted, projectId).get() != startedBefore;
        long upTo = journal.lastSeq(projectId);
        Path draft = storage.draftOntologyPath(projectId);
        Path staged = null;
        try {
            staged = Files.createTempFile(draft.getParent(), "compact-", ".staged");
            try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                OutputStream out = new BufferedOutputStream(new StopWhen(Channels.newOutputStream(channel), interrupted));
                entry.get().manager().saveOntology(entry.get().ontology(), new RDFXMLDocumentFormat(), out);
                out.flush();
                channel.force(true);
            }
            if (interrupted.getAsBoolean()) {
                scheduleAfterEdit(projectId);
                return false;
            }
            AtomicFiles.replace(staged, draft);
            journal.written(projectId, upTo);
            log.info("[Journal] Folded the unsaved edits of {} into its draft (up to edit {})", projectId, upTo);
            DesktopFusekiSyncScheduler sync = syncScheduler.getIfAvailable();
            if (sync != null) {
                sync.scheduleAfterMutation(projectId);
            }
            return true;
        } catch (Exception e) {
            if (interrupted.getAsBoolean()) {
                log.debug("[Journal] Folding the edits of {} paused for a new edit", projectId);
            } else {
                log.warn("[Journal] Could not fold the edits of {} into its draft yet: {}", projectId, e.getMessage());
            }
            scheduleAfterEdit(projectId);
            return false;
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                    log.debug("[Journal] Could not remove {}", staged);
                }
            }
        }
    }

    private static AtomicLong counter(ConcurrentHashMap<String, AtomicLong> counters, String projectId) {
        return counters.computeIfAbsent(projectId, id -> new AtomicLong());
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    private static final class StopWhen extends FilterOutputStream {

        private final BooleanSupplier stop;

        StopWhen(OutputStream out, BooleanSupplier stop) {
            super(out);
            this.stop = stop;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (stop.getAsBoolean()) {
                throw new IOException("A new edit arrived");
            }
            out.write(b, off, len);
        }

        @Override
        public void write(int b) throws IOException {
            if (stop.getAsBoolean()) {
                throw new IOException("A new edit arrived");
            }
            out.write(b);
        }
    }
}
