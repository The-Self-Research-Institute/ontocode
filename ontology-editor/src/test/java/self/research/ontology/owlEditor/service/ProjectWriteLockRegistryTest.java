package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
