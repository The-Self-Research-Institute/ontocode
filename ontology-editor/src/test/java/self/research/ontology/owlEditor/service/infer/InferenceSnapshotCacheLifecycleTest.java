package self.research.ontology.owlEditor.service.infer;

import org.eclipse.rdf4j.repository.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import self.research.ontology.common.admission.CacheAccount;
import self.research.ontology.owlEditor.service.RetainedMemory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InferenceSnapshotCacheLifecycleTest {

    @TempDir
    Path tmp;

    static InferenceSnapshotKey key(String project) {
        return InferenceSnapshotKey.of(project, false, "", "rev", "imports", "", "none");
    }

    static InferredSnapshot snapshot(TieredSnapshotStores stores, String project, long triples) {
        Repository repo = stores.create(10_000_000L);
        return new InferredSnapshot(key(project), repo, List.of("g"), triples, 0, Instant.now(), null);
    }

    long nativeDirs() throws IOException {
        try (Stream<Path> roots = Files.list(tmp)) {
            return roots.filter(p -> p.getFileName().toString().startsWith("ontocode-inference-"))
                    .flatMap(r -> {
                        try {
                            return Files.list(r).filter(Files::isDirectory);
                        } catch (IOException e) {
                            return Stream.empty();
                        }
                    }).count();
        }
    }

    @Test
    void evictionWithoutBorrowersShutsDownRepositoryAndRemovesTempDir() throws Exception {
        CacheAccount account = RetainedMemory.detachedAccount("snapA");
        InferenceSnapshotCache cache = new InferenceSnapshotCache(1, 1_000_000, 1_000_000, () -> 0L);
        cache.setAccounting(new LedgerInferenceCacheAccounting(account));
        TieredSnapshotStores stores = new TieredSnapshotStores(0, tmp);
        InferredSnapshot first = cache.put(snapshot(stores, "p1", 10));
        first.release();
        assertEquals(1, nativeDirs());
        assertTrue(account.bytes() > 0);
        InferredSnapshot second = cache.put(snapshot(stores, "p2", 10));
        second.release();
        assertFalse(first.repository().isInitialized());
        assertEquals(1, nativeDirs());
        cache.clear();
        assertEquals(0, nativeDirs());
        assertEquals(0, account.bytes());
        assertEquals(0, account.underflowCount());
    }

    @Test
    void evictionWithABorrowerWaitsForTheLastRelease() throws Exception {
        CacheAccount account = RetainedMemory.detachedAccount("snapB");
        InferenceSnapshotCache cache = new InferenceSnapshotCache(1, 1_000_000, 1_000_000, () -> 0L);
        cache.setAccounting(new LedgerInferenceCacheAccounting(account));
        TieredSnapshotStores stores = new TieredSnapshotStores(0, tmp);
        InferredSnapshot first = cache.put(snapshot(stores, "p1", 10));
        InferredSnapshot second = cache.put(snapshot(stores, "p2", 10));
        assertTrue(first.repository().isInitialized());
        assertEquals(2, nativeDirs());
        first.release();
        assertFalse(first.repository().isInitialized());
        assertEquals(1, nativeDirs());
        second.release();
        cache.clear();
        assertEquals(0, nativeDirs());
        assertEquals(0, account.bytes());
    }

    @Test
    void ttlExpiryRetiresToo() throws Exception {
        long[] now = {0};
        CacheAccount account = RetainedMemory.detachedAccount("snapC");
        InferenceSnapshotCache cache = new InferenceSnapshotCache(3, 1000, 1_000_000, () -> now[0]);
        cache.setAccounting(new LedgerInferenceCacheAccounting(account));
        TieredSnapshotStores stores = new TieredSnapshotStores(0, tmp);
        cache.put(snapshot(stores, "p1", 10)).release();
        now[0] = 5000;
        assertEquals(0, cache.size());
        assertEquals(0, nativeDirs());
        assertEquals(0, account.bytes());
    }

    @Test
    void startupSweepRemovesOrphanDirsAndKeepsLiveOnes() throws Exception {
        Path orphan = Files.createDirectories(tmp.resolve("ontocode-inference-dead/sub"));
        Files.writeString(orphan.getParent().resolve(".owner"), "999999999:1");
        Files.writeString(orphan.resolve("data"), "x");
        Path live = Files.createDirectories(tmp.resolve("ontocode-inference-live"));
        ProcessHandle self = ProcessHandle.current();
        Files.writeString(live.resolve(".owner"), self.pid() + ":" + self.info().startInstant().get().toEpochMilli());
        Path unrelated = Files.createDirectories(tmp.resolve("other-dir"));
        assertEquals(1, TieredSnapshotStores.sweepOrphans(tmp));
        assertFalse(Files.exists(orphan.getParent()));
        assertTrue(Files.exists(live));
        assertTrue(Files.exists(unrelated));
    }

    @Test
    void constructingTheStoresSweepsOrphansFromACrashedRun() throws Exception {
        Path orphan = Files.createDirectories(tmp.resolve("ontocode-inference-crashed"));
        Files.writeString(orphan.resolve(".owner"), "999999999:1");
        new TieredSnapshotStores(0, tmp);
        assertFalse(Files.exists(orphan));
    }
}
