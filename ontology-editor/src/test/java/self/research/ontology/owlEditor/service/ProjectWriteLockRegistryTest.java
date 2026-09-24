package self.research.ontology.owlEditor.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            Future<?> writer = pool.submit(() -> registry.runExclusive("proj-1", () -> null));
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
}
