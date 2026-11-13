package self.research.ontology.owlEditor.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.ObjectProvider;
import self.research.ontology.owlEditor.repository.ProjectWriteLeaseRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectWriteLockRegistryTest {

    @Test
    @Timeout(5)
    void twoSharedReadersRunConcurrentlyWithoutBlockingEachOther() throws Exception {
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry();
        CountDownLatch bothEntered = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> registry.runShared("proj-1", () -> {
                bothEntered.countDown();
                bothEntered.await(4, TimeUnit.SECONDS);
                return null;
            }));
            Future<?> b = pool.submit(() -> registry.runShared("proj-1", () -> {
                bothEntered.countDown();
                bothEntered.await(4, TimeUnit.SECONDS);
                return null;
            }));
            a.get(4, TimeUnit.SECONDS);
            b.get(4, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(5)
    void exclusiveWriteWaitsForAnInProgressSharedRead() throws Exception {
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry();
        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        AtomicBoolean writerRanWhileReaderActive = new AtomicBoolean(false);
        AtomicBoolean readerStillActive = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> reader = pool.submit(() -> registry.runShared("proj-1", () -> {
                readerStarted.countDown();
                releaseReader.await(4, TimeUnit.SECONDS);
                readerStillActive.set(false);
                return null;
            }));
            assertTrue(readerStarted.await(4, TimeUnit.SECONDS));

            Future<?> writer = pool.submit(() -> registry.runExclusive("proj-1", () -> {
                writerRanWhileReaderActive.set(readerStillActive.get());
                return null;
            }));

            Thread.sleep(200);
            releaseReader.countDown();
            reader.get(4, TimeUnit.SECONDS);
            writer.get(4, TimeUnit.SECONDS);

            assertFalse(writerRanWhileReaderActive.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(5)
    void sharedReadWaitsForAnInProgressExclusiveWrite() throws Exception {
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry();
        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AtomicBoolean writerStillActive = new AtomicBoolean(true);
        AtomicBoolean readerRanWhileWriterActive = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> registry.runExclusive("proj-1", () -> {
                writerStarted.countDown();
                releaseWriter.await(4, TimeUnit.SECONDS);
                writerStillActive.set(false);
                return null;
            }));
            assertTrue(writerStarted.await(4, TimeUnit.SECONDS));

            Future<?> reader = pool.submit(() -> registry.runShared("proj-1", () -> {
                readerRanWhileWriterActive.set(writerStillActive.get());
                return null;
            }));

            Thread.sleep(200);
            releaseWriter.countDown();
            writer.get(4, TimeUnit.SECONDS);
            reader.get(4, TimeUnit.SECONDS);

            assertFalse(readerRanWhileWriterActive.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(5)
    void exclusiveWorkRecordsWaitAndHoldTimersTaggedExclusive() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry(meters);

        String result = registry.runExclusive("proj-1", () -> {
            Thread.sleep(60);
            return "done";
        });

        assertEquals("done", result);
        Timer hold = timer(meters, "assistant.lock.hold", "exclusive");
        Timer wait = timer(meters, "assistant.lock.wait", "exclusive");
        assertEquals(1, hold.count());
        assertEquals(1, wait.count());
        assertTrue(hold.totalTime(TimeUnit.MILLISECONDS) >= 50);
        assertEquals(0, timer(meters, "assistant.lock.hold", "shared").count());
    }

    @Test
    @Timeout(5)
    void sharedWorkRecordsWaitAndHoldTimersTaggedShared() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry(meters);

        registry.runShared("proj-1", () -> null);
        registry.runShared("proj-2", () -> null);

        assertEquals(2, timer(meters, "assistant.lock.wait", "shared").count());
        assertEquals(2, timer(meters, "assistant.lock.hold", "shared").count());
        assertEquals(0, timer(meters, "assistant.lock.wait", "exclusive").count());
    }

    @Test
    @Timeout(5)
    void holdTimeIsRecordedEvenWhenTheWorkThrows() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry(meters);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> registry.runExclusive("proj-1", () -> {
                    throw new IllegalStateException("boom");
                }));

        assertEquals("boom", thrown.getMessage());
        assertEquals(1, timer(meters, "assistant.lock.hold", "exclusive").count());
    }

    @Test
    @Timeout(5)
    void exclusiveWaitTimerCapturesTimeSpentBlockedBehindAReader() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry(meters);
        CountDownLatch readerStarted = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> reader = pool.submit(() -> registry.runShared("proj-1", () -> {
                readerStarted.countDown();
                releaseReader.await(4, TimeUnit.SECONDS);
                return null;
            }));
            assertTrue(readerStarted.await(4, TimeUnit.SECONDS));
            CountDownLatch writerAboutToLock = new CountDownLatch(1);
            Future<?> writer = pool.submit(() -> {
                writerAboutToLock.countDown();
                return registry.runExclusive("proj-1", () -> null);
            });
            assertTrue(writerAboutToLock.await(4, TimeUnit.SECONDS));
            Thread.sleep(250);
            releaseReader.countDown();
            reader.get(4, TimeUnit.SECONDS);
            writer.get(4, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertTrue(timer(meters, "assistant.lock.wait", "exclusive").max(TimeUnit.MILLISECONDS) >= 200);
    }

    @Test
    @Timeout(5)
    void noArgConstructorWorksWithoutAnyMeterRegistry() throws Exception {
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry();

        assertEquals(3, (int) registry.runExclusive("proj-1", () -> registry.runShared("proj-1", () -> 3)));
    }

    @Test
    @Timeout(5)
    void exclusiveIsReentrantOnTheSameThread() throws Exception {
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry();

        assertEquals("inner", registry.runExclusive("proj-1",
                () -> registry.runExclusive("proj-1", () -> "inner")));
    }

    @Test
    @Timeout(5)
    void differentProjectsDoNotBlockEachOther() throws Exception {
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry();
        CountDownLatch bothEntered = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> registry.runExclusive("proj-1", () -> {
                bothEntered.countDown();
                return bothEntered.await(4, TimeUnit.SECONDS);
            }));
            Future<Boolean> b = pool.submit(() -> registry.runExclusive("proj-2", () -> {
                bothEntered.countDown();
                return bothEntered.await(4, TimeUnit.SECONDS);
            }));
            assertTrue(a.get(4, TimeUnit.SECONDS));
            assertTrue(b.get(4, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static Timer timer(MeterRegistry meters, String name, String mode) {
        Timer timer = meters.find(name).tag("mode", mode).timer();
        assertNotNull(timer, name + " " + mode);
        return timer;
    }

    private static ProjectWriteLeaseManager leaseManager(LeaseTable collection, Duration acquireTimeout) {
        return new ProjectWriteLeaseManager(collection.repository, Clock.systemUTC(),
                new ProjectWriteLeaseManager.Settings(Duration.ofSeconds(30), Duration.ofSeconds(10), acquireTimeout,
                        Duration.ofMillis(10), Duration.ofMillis(40)),
                null);
    }

    @Test
    @Timeout(5)
    void databaseModeHoldsTheProjectLeaseOnlyWhileExclusiveWorkRuns() throws Exception {
        LeaseTable collection = new LeaseTable();
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry(leaseManager(collection, Duration.ofSeconds(1)), null);
        try {
            AtomicReference<LeaseTable.LeaseRow> duringWork = new AtomicReference<>();

            registry.runExclusive("proj-1", () -> {
                duringWork.set(collection.get("proj-1"));
                return null;
            });

            assertEquals("database", registry.getMode());
            assertNotNull(duringWork.get());
            assertNotNull(duringWork.get().getOwnerToken());
            assertNull(collection.get("proj-1"));
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @Timeout(5)
    void databaseModeReleasesTheLeaseAndLocalLockWhenTheWorkThrows() throws Exception {
        LeaseTable collection = new LeaseTable();
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry(leaseManager(collection, Duration.ofSeconds(1)), null);
        try {
            assertThrows(IllegalStateException.class, () -> registry.runExclusive("proj-1", () -> {
                throw new IllegalStateException("apply failed");
            }));

            assertNull(collection.get("proj-1"));
            assertEquals("again", registry.runExclusive("proj-1", () -> "again"));
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @Timeout(5)
    void databaseModeNestedExclusiveOnTheSameThreadTakesTheLeaseOnce() throws Exception {
        LeaseTable collection = new LeaseTable();
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry(leaseManager(collection, Duration.ofMillis(300)), null);
        try {
            String result = registry.runExclusive("proj-1", () -> registry.runExclusive("proj-1", () -> {
                assertNotNull(collection.get("proj-1"));
                return "nested";
            }));

            assertEquals("nested", result);
            assertEquals(1, collection.insertAttempts.get());
            assertNull(collection.get("proj-1"));
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @Timeout(10)
    void twoNodesSharingTheLeaseStoreNeverRunExclusiveWorkForTheSameProjectAtOnce() throws Exception {
        LeaseTable collection = new LeaseTable();
        ProjectWriteLockRegistry nodeA = new ProjectWriteLockRegistry(leaseManager(collection, Duration.ofSeconds(5)), null);
        ProjectWriteLockRegistry nodeB = new ProjectWriteLockRegistry(leaseManager(collection, Duration.ofSeconds(5)), null);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                ProjectWriteLockRegistry node = worker % 2 == 0 ? nodeA : nodeB;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 5; i++) {
                        node.runExclusive("proj-1", () -> {
                            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                            Thread.sleep(5);
                            active.decrementAndGet();
                            return completed.incrementAndGet();
                        });
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(9, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            nodeA.shutdown();
            nodeB.shutdown();
        }

        assertEquals(20, completed.get());
        assertEquals(1, maxActive.get());
        assertNull(collection.get("proj-1"));
    }

    @Test
    @Timeout(5)
    void databaseModeExclusiveOnAnotherNodeWaitsUntilTheFirstNodeReleases() throws Exception {
        LeaseTable collection = new LeaseTable();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ProjectWriteLockRegistry nodeA = new ProjectWriteLockRegistry(leaseManager(collection, Duration.ofSeconds(3)), null);
        ProjectWriteLockRegistry nodeB = new ProjectWriteLockRegistry(leaseManager(collection, Duration.ofSeconds(3)), meters);
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        AtomicBoolean bRanWhileAActive = new AtomicBoolean(false);
        AtomicBoolean aActive = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> nodeA.runExclusive("proj-1", () -> {
                aEntered.countDown();
                releaseA.await(4, TimeUnit.SECONDS);
                aActive.set(false);
                return null;
            }));
            assertTrue(aEntered.await(4, TimeUnit.SECONDS));
            CountDownLatch bAboutToLock = new CountDownLatch(1);
            Future<?> b = pool.submit(() -> {
                bAboutToLock.countDown();
                return nodeB.runExclusive("proj-1", () -> {
                    bRanWhileAActive.set(aActive.get());
                    return null;
                });
            });
            assertTrue(bAboutToLock.await(4, TimeUnit.SECONDS));
            Thread.sleep(250);
            assertFalse(b.isDone());
            releaseA.countDown();
            a.get(4, TimeUnit.SECONDS);
            b.get(4, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            nodeA.shutdown();
            nodeB.shutdown();
        }

        assertFalse(bRanWhileAActive.get());
        assertTrue(timer(meters, "assistant.lock.wait", "exclusive").max(TimeUnit.MILLISECONDS) >= 200);
    }

    @Test
    @Timeout(5)
    void databaseModeLeaseTimeoutSkipsTheWorkAndFreesTheLocalLock() throws Exception {
        LeaseTable collection = new LeaseTable();
        collection.put("proj-1", "other-node-token", Instant.now().plusSeconds(600));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry(leaseManager(collection, Duration.ofMillis(200)), meters);
        AtomicBoolean workRan = new AtomicBoolean(false);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            assertThrows(ProjectWriteLeaseUnavailableException.class, () -> registry.runExclusive("proj-1", () -> {
                workRan.set(true);
                return null;
            }));

            assertFalse(workRan.get());
            assertEquals("other-node-token", collection.get("proj-1").getOwnerToken());
            assertEquals(1, timer(meters, "assistant.lock.wait", "exclusive").count());
            assertEquals(0, timer(meters, "assistant.lock.hold", "exclusive").count());
            assertEquals("read", pool.submit(() -> registry.runShared("proj-1", () -> "read")).get(2, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            registry.shutdown();
        }
    }

    @Test
    @Timeout(10)
    void databaseModeLocalReadsKeepRunningWhileAWriterWaitsForAnotherNodesLease() throws Exception {
        LeaseTable collection = new LeaseTable();
        collection.put("proj-1", "other-node-token", Instant.now().plusSeconds(600));
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry(leaseManager(collection, Duration.ofSeconds(8)), null);
        AtomicBoolean writerRan = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = pool.submit(() -> registry.runExclusive("proj-1", () -> {
                writerRan.set(true);
                return null;
            }));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (collection.insertAttempts.get() < 2) {
                assertTrue(System.nanoTime() < deadline, "writer never started waiting for the lease");
                Thread.sleep(5);
            }

            assertEquals("read", pool.submit(() -> registry.runShared("proj-1", () -> "read")).get(2, TimeUnit.SECONDS));
            assertFalse(writerRan.get());

            collection.delete("proj-1");
            writer.get(4, TimeUnit.SECONDS);
            assertTrue(writerRan.get());
            assertNull(collection.get("proj-1"));
        } finally {
            pool.shutdownNow();
            registry.shutdown();
        }
    }

    @Test
    @Timeout(5)
    void databaseModeRefusesToUpgradeASharedReadInsteadOfDeadlockingWhileHoldingTheLease() throws Exception {
        LeaseTable collection = new LeaseTable();
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry(leaseManager(collection, Duration.ofSeconds(1)), null);
        try {
            assertThrows(IllegalStateException.class, () -> registry.runShared("proj-1",
                    () -> registry.runExclusive("proj-1", () -> "never")));

            assertEquals(0, collection.insertAttempts.get());
            assertNull(collection.get("proj-1"));
            assertEquals("after", registry.runExclusive("proj-1", () -> "after"));
            assertEquals(3, (int) registry.runExclusive("proj-1", () -> registry.runShared("proj-1", () -> 3)));
        } finally {
            registry.shutdown();
        }
    }

    @Test
    @Timeout(5)
    void databaseModeSharedReadsStayLocalAndNeverTouchTheLeaseStore() throws Exception {
        LeaseTable collection = new LeaseTable();
        collection.put("proj-1", "other-node-token", Instant.now().plusSeconds(600));
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry(leaseManager(collection, Duration.ofMillis(100)), null);
        try {
            assertEquals("read", registry.runShared("proj-1", () -> "read"));

            assertEquals(0, collection.insertAttempts.get());
            assertEquals("other-node-token", collection.get("proj-1").getOwnerToken());
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void springConstructorDefaultsToLocalModeAndNeverAsksForTheLeaseRepository() throws Exception {
        ObjectProvider<ProjectWriteLeaseRepository> leases = provider(null);
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry("local", 30000, 10000, 120000, leases, provider(null));

        assertEquals("local", registry.getMode());
        assertEquals(1, (int) registry.runExclusive("proj-1", () -> 1));
    }

    @Test
    void springConstructorBuildsDatabaseModeFromTheProperty() throws Exception {
        LeaseTable collection = new LeaseTable();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry(" Database ", 30000, 10000, 1000,
                provider(collection.repository), provider(meters));
        try {
            AtomicReference<LeaseTable.LeaseRow> duringWork = new AtomicReference<>();
            registry.runExclusive("proj-1", () -> {
                duringWork.set(collection.get("proj-1"));
                return null;
            });

            assertEquals("database", registry.getMode());
            assertNotNull(duringWork.get());
            assertEquals(1, timer(meters, "assistant.lock.hold", "exclusive").count());
        } finally {
            registry.shutdown();
        }
    }

    @Test
    void springConstructorRejectsDatabaseModeWithoutALeaseRepositoryAndUnknownModes() {
        assertThrows(IllegalStateException.class,
                () -> new ProjectWriteLockRegistry("database", 30000, 10000, 1000, provider(null), provider(null)));
        assertThrows(IllegalStateException.class,
                () -> new ProjectWriteLockRegistry("redis", 30000, 10000, 1000, provider(null), provider(null)));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectWriteLockRegistry("database", 10000, 10000, 1000,
                        provider(new LeaseTable().repository), provider(null)));
    }

    @Test
    void theOldMongoModeNameStillSelectsTheDatabaseLeases() throws Exception {
        LeaseTable collection = new LeaseTable();
        ProjectWriteLockRegistry registry = new ProjectWriteLockRegistry("mongo", 30000, 10000, 1000,
                provider(collection.repository), provider(null));
        try {
            assertEquals("database", registry.getMode());
            registry.runExclusive("proj-1", () -> {
                assertNotNull(collection.get("proj-1"));
                return null;
            });
            assertNull(collection.get("proj-1"));
        } finally {
            registry.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
