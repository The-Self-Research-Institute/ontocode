package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class StorageManagerPublicGraphVersionTest {

    @TempDir
    Path dataDir;

    private FakeVersionStore durableStore;

    @BeforeEach
    void setUp() {
        durableStore = new FakeVersionStore();
    }

    private StorageManager startInstance() throws Exception {
        StorageManager manager = new StorageManager(dataDir.toString(), mock(SparqlDatasetService.class));
        manager.setPublicGraphVersionStore(durableStore);
        return manager;
    }

    @Test
    void freshInstanceAfterRestartSeesThePersistedVersionInsteadOfZero() throws Exception {
        StorageManager beforeRestart = startInstance();
        beforeRestart.clearCodeViewCache("proj-a");
        beforeRestart.clearCodeViewCache("proj-a");
        beforeRestart.clearCodeViewCache("proj-a");
        long seenBeforeRestart = beforeRestart.getPublicGraphVersion("proj-a");

        StorageManager afterRestart = startInstance();

        assertEquals(3L, seenBeforeRestart);
        assertEquals(seenBeforeRestart, afterRestart.getPublicGraphVersion("proj-a"));
    }

    @Test
    void bumpAfterRestartContinuesFromThePersistedValue() throws Exception {
        StorageManager beforeRestart = startInstance();
        beforeRestart.clearCodeViewCache("proj-a");
        beforeRestart.clearCodeViewCache("proj-a");

        StorageManager afterRestart = startInstance();
        afterRestart.clearCodeViewCache("proj-a");

        assertEquals(3L, afterRestart.getPublicGraphVersion("proj-a"));
    }

    @Test
    void projectsKeepIndependentVersions() throws Exception {
        StorageManager manager = startInstance();
        manager.clearCodeViewCache("proj-a");
        manager.clearCodeViewCache("proj-a");
        manager.clearCodeViewCache("proj-b");

        assertEquals(2L, manager.getPublicGraphVersion("proj-a"));
        assertEquals(1L, manager.getPublicGraphVersion("proj-b"));
        assertEquals(0L, manager.getPublicGraphVersion("proj-never-touched"));
    }

    @Test
    void readsAreServedFromMemoryAfterTheFirstLookup() throws Exception {
        StorageManager beforeRestart = startInstance();
        beforeRestart.clearCodeViewCache("proj-a");
        StorageManager afterRestart = startInstance();
        int readsBefore = durableStore.reads.get();

        for (int i = 0; i < 50; i++) {
            afterRestart.getPublicGraphVersion("proj-a");
        }

        assertEquals(readsBefore + 1, durableStore.reads.get());
    }

    @Test
    void concurrentBumpsNeverLoseIncrementsAndNeverGoBackwards() throws Exception {
        StorageManager manager = startInstance();
        int threads = 16;
        int bumpsPerThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean wentBackwards = new AtomicBoolean(false);
        AtomicBoolean writersDone = new AtomicBoolean(false);
        List<Long> observed = Collections.synchronizedList(new ArrayList<>());

        pool.submit(() -> {
            long last = 0;
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            while (!writersDone.get()) {
                long current = manager.getPublicGraphVersion("proj-c");
                if (current < last) {
                    wentBackwards.set(true);
                }
                last = current;
                observed.add(current);
            }
        });
        CountDownLatch finished = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < bumpsPerThread; i++) {
                        manager.clearCodeViewCache("proj-c");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(finished.await(60, TimeUnit.SECONDS));
        writersDone.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        long expected = (long) threads * bumpsPerThread;
        assertFalse(wentBackwards.get());
        assertEquals(expected, durableStore.persisted("proj-c"));
        assertEquals(expected, manager.getPublicGraphVersion("proj-c"));
        assertEquals(expected, startInstance().getPublicGraphVersion("proj-c"));
    }

    @Test
    void concurrentFirstReadAfterRestartNeverHidesANewerBump() throws Exception {
        StorageManager beforeRestart = startInstance();
        for (int i = 0; i < 10; i++) {
            beforeRestart.clearCodeViewCache("proj-d");
        }
        durableStore.slowReads = true;
        StorageManager afterRestart = startInstance();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch readStarted = durableStore.readStarted;
        pool.submit(() -> afterRestart.getPublicGraphVersion("proj-d"));
        assertTrue(readStarted.await(10, TimeUnit.SECONDS));
        afterRestart.clearCodeViewCache("proj-d");
        durableStore.releaseRead.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(11L, afterRestart.getPublicGraphVersion("proj-d"));
    }

    @Test
    void failedPersistStillChangesTheVersionSoAConflictIsNotMissed() throws Exception {
        StorageManager manager = startInstance();
        manager.clearCodeViewCache("proj-e");
        long before = manager.getPublicGraphVersion("proj-e");
        durableStore.failIncrements = true;

        manager.clearCodeViewCache("proj-e");

        assertNotEquals(before, manager.getPublicGraphVersion("proj-e"));
        assertTrue(manager.getPublicGraphVersion("proj-e") > before);
    }

    @Test
    void withoutADurableStoreTheVersionStillAdvancesInMemory() throws Exception {
        StorageManager manager = new StorageManager(dataDir.toString(), mock(SparqlDatasetService.class));
        assertEquals(0L, manager.getPublicGraphVersion("proj-f"));

        manager.clearCodeViewCache("proj-f");
        long first = manager.getPublicGraphVersion("proj-f");
        manager.clearCodeViewCache("proj-f");

        assertTrue(first > 0);
        assertTrue(manager.getPublicGraphVersion("proj-f") > first);
    }

    static final class FakeVersionStore extends PublicGraphVersionStore {
        private final Map<String, AtomicLong> versions = new ConcurrentHashMap<>();
        final AtomicInteger reads = new AtomicInteger();
        volatile boolean failIncrements;
        volatile boolean slowReads;
        final CountDownLatch readStarted = new CountDownLatch(1);
        final CountDownLatch releaseRead = new CountDownLatch(1);

        FakeVersionStore() {
            super(null);
        }

        @Override
        public long increment(String projectId) {
            if (failIncrements) {
                throw new IllegalStateException("mongo unavailable");
            }
            return versions.computeIfAbsent(projectId, k -> new AtomicLong()).incrementAndGet();
        }

        @Override
        public long read(String projectId) {
            reads.incrementAndGet();
            long value = persisted(projectId);
            if (slowReads) {
                readStarted.countDown();
                try {
                    releaseRead.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return value;
        }

        long persisted(String projectId) {
            AtomicLong value = versions.get(projectId);
            return value != null ? value.get() : 0L;
        }
    }
}
