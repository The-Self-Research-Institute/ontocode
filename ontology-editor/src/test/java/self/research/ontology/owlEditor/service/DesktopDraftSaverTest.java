package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesktopDraftSaverTest {

    private final DesktopOntologyLoader loader = mock(DesktopOntologyLoader.class);
    private final ProjectOntologyCache cache = mock(ProjectOntologyCache.class);

    private DesktopDraftSaver saver(long firstRetryMillis) {
        when(cache.has("p")).thenReturn(true);
        return new DesktopDraftSaver(loader, cache, firstRetryMillis);
    }

    private static void waitUntil(java.util.function.BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void aSaveThatFailsIsRetriedUntilTheChangesAreOnDisk() throws Exception {
        doThrow(new IOException("another program has it open")).doNothing().when(loader).persistToDisk("p");
        DesktopDraftSaver saver = saver(50);

        saver.save("p");
        assertEquals("another program has it open", saver.failure("p").orElseThrow());

        waitUntil(() -> saver.failure("p").isEmpty());
        assertTrue(saver.failure("p").isEmpty());
        verify(loader, times(2)).persistToDisk("p");
    }

    @Test
    void saveWritesPendingChangesFirstAndRefusesWhenTheyStillCannotBeWritten() throws Exception {
        doThrow(new IOException("disk full")).when(loader).persistToDisk("p");
        DesktopDraftSaver saver = saver(60_000);
        saver.save("p");

        IOException error = assertThrows(IOException.class, () -> saver.flush("p"));
        assertTrue(error.getMessage().contains("disk full"), error.getMessage());

        doNothing().when(loader).persistToDisk("p");
        saver.flush("p");
        assertTrue(saver.failure("p").isEmpty());
    }

    @Test
    void flushDoesNothingWhenEverythingIsAlreadySaved() throws Exception {
        DesktopDraftSaver saver = saver(60_000);
        saver.save("p");

        saver.flush("p");

        verify(loader, times(1)).persistToDisk("p");
    }

    @Test
    void discardingTheDraftStopsTheRetries() throws Exception {
        doThrow(new IOException("locked")).when(loader).persistToDisk("p");
        DesktopDraftSaver saver = saver(50);
        saver.save("p");

        saver.forget("p");
        Thread.sleep(300);

        verify(loader, times(1)).persistToDisk("p");
        assertTrue(saver.failure("p").isEmpty());
    }

    @Test
    void retriesStopWhenTheOntologyIsNoLongerOpen() throws Exception {
        doThrow(new IOException("locked")).when(loader).persistToDisk("p");
        DesktopDraftSaver saver = saver(50);
        saver.save("p");
        when(cache.has("p")).thenReturn(false);

        waitUntil(() -> saver.failure("p").isEmpty());

        assertTrue(saver.failure("p").isEmpty());
        verify(loader, times(1)).persistToDisk("p");
    }

    @Test
    void twoSavesOfTheSameProjectNeverRunAtTheSameTime() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        CountDownLatch bothStarted = new CountDownLatch(2);
        doAnswer(inv -> {
            mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
            Thread.sleep(100);
            running.decrementAndGet();
            return null;
        }).when(loader).persistToDisk("p");
        DesktopDraftSaver saver = saver(60_000);

        Thread first = new Thread(() -> { bothStarted.countDown(); saver.save("p"); });
        Thread second = new Thread(() -> { bothStarted.countDown(); saver.save("p"); });
        first.start();
        second.start();
        first.join(5_000);
        second.join(5_000);

        assertEquals(1, mostAtOnce.get());
        assertFalse(first.isAlive() || second.isAlive());
    }
}
