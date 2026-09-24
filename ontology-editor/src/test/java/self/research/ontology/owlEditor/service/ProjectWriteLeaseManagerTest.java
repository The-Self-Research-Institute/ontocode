package self.research.ontology.owlEditor.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataAccessResourceFailureException;
import self.research.ontology.owlEditor.document.ProjectWriteLeaseDocument;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectWriteLeaseManagerTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);

    private final InMemoryLeaseCollection collection = new InMemoryLeaseCollection();
    private final InMemoryLeaseCollection.MutableClock clock = new InMemoryLeaseCollection.MutableClock(START);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ProjectWriteLeaseManager manager;

    @AfterEach
    void closeManager() {
        if (manager != null) {
            manager.close();
        }
    }

    private ProjectWriteLeaseManager manager(Duration renewInterval, Duration acquireTimeout) {
        manager = new ProjectWriteLeaseManager(collection.template, clock,
                new ProjectWriteLeaseManager.Settings(TTL, renewInterval, acquireTimeout,
                        Duration.ofMillis(10), Duration.ofMillis(40)),
                meters);
        return manager;
    }

    @Test
    @Timeout(5)
    void acquireStoresALeaseWithAUniqueOwnerTokenAndExpiry() throws Exception {
        ProjectWriteLeaseManager.Lease lease = manager(Duration.ofSeconds(10), Duration.ofSeconds(1)).acquire("proj-1");

        ProjectWriteLeaseDocument stored = collection.get("proj-1");
        assertNotNull(stored);
        assertEquals(lease.getOwnerToken(), stored.getOwnerToken());
        assertEquals(START, stored.getAcquiredAt());
        assertEquals(START.plus(TTL), stored.getExpiresAt());
        assertNotNull(stored.getHolder());
        assertFalse(lease.isLost());

        lease.release();
        assertNull(collection.get("proj-1"));

        ProjectWriteLeaseManager.Lease next = manager.acquire("proj-1");
        assertNotEquals(lease.getOwnerToken(), next.getOwnerToken());
        next.release();
    }

    @Test
    @Timeout(5)
    void releaseLeavesALeaseThatAnotherOwnerNowHolds() throws Exception {
        ProjectWriteLeaseManager.Lease lease = manager(Duration.ofSeconds(10), Duration.ofSeconds(1)).acquire("proj-1");
        collection.put("proj-1", "someone-else", START.plusSeconds(600));

        lease.release();

        ProjectWriteLeaseDocument stored = collection.get("proj-1");
        assertNotNull(stored);
        assertEquals("someone-else", stored.getOwnerToken());
    }

    @Test
    @Timeout(5)
    void releaseIsIdempotent() throws Exception {
        ProjectWriteLeaseManager.Lease lease = manager(Duration.ofSeconds(10), Duration.ofSeconds(1)).acquire("proj-1");
        lease.release();
        collection.put("proj-1", "someone-else", START.plusSeconds(600));

        lease.release();

        assertEquals("someone-else", collection.get("proj-1").getOwnerToken());
    }

    @Test
    @Timeout(5)
    void anExpiredLeaseFromAnotherOwnerIsTakenOver() throws Exception {
        collection.put("proj-1", "crashed-node", START.minusSeconds(1));

        ProjectWriteLeaseManager.Lease lease = manager(Duration.ofSeconds(10), Duration.ofSeconds(1)).acquire("proj-1");

        ProjectWriteLeaseDocument stored = collection.get("proj-1");
        assertEquals(lease.getOwnerToken(), stored.getOwnerToken());
        assertEquals(START, stored.getAcquiredAt());
        assertEquals(START.plus(TTL), stored.getExpiresAt());
        assertNotEquals("other-node", stored.getHolder());
        lease.release();
        assertNull(collection.get("proj-1"));
    }

    @Test
    @Timeout(5)
    void aLiveLeaseHeldElsewhereTimesOutWithAClearException() {
        collection.put("proj-1", "busy-node", START.plusSeconds(600));
        ProjectWriteLeaseManager leases = manager(Duration.ofSeconds(10), Duration.ofMillis(300));

        long started = System.nanoTime();
        ProjectWriteLeaseUnavailableException thrown =
                assertThrows(ProjectWriteLeaseUnavailableException.class, () -> leases.acquire("proj-1"));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals("proj-1", thrown.getProjectId());
        assertEquals(Duration.ofMillis(300), thrown.getWaited());
        assertTrue(elapsedMillis >= 290, "waited only " + elapsedMillis + " ms");
        assertTrue(collection.insertAttempts.get() > 2, "should have retried with backoff");
        assertEquals("busy-node", collection.get("proj-1").getOwnerToken());
        assertEquals(1.0, meters.counter(ProjectWriteLeaseManager.TIMEOUT_COUNTER).count());
    }

    @Test
    @Timeout(5)
    void acquireKeepsRetryingUntilTheOtherHolderReleases() throws Exception {
        collection.put("proj-1", "busy-node", START.plusSeconds(600));
        ProjectWriteLeaseManager leases = manager(Duration.ofSeconds(10), Duration.ofSeconds(3));
        CompletableFuture.runAsync(() -> {
            sleep(200);
            collection.delete("proj-1");
        });

        ProjectWriteLeaseManager.Lease lease = leases.acquire("proj-1");

        assertEquals(lease.getOwnerToken(), collection.get("proj-1").getOwnerToken());
        assertTrue(collection.insertAttempts.get() > 1);
        lease.release();
    }

    @Test
    @Timeout(5)
    void aLeaseIsRenewedWhileTheWorkIsStillRunning() throws Exception {
        ProjectWriteLeaseManager.Lease lease = manager(Duration.ofMillis(50), Duration.ofSeconds(1)).acquire("proj-1");
        clock.advanceSeconds(3600);
        Instant expected = START.plusSeconds(3600).plus(TTL);

        awaitTrue(() -> expected.equals(collection.get("proj-1").getExpiresAt()));

        assertTrue(collection.renewals.get() >= 1);
        assertEquals(lease.getOwnerToken(), collection.get("proj-1").getOwnerToken());
        lease.release();
        int renewalsAtRelease = collection.renewals.get();
        Thread.sleep(200);
        assertTrue(collection.renewals.get() <= renewalsAtRelease + 1, "renewal must stop after release");
        assertNull(collection.get("proj-1"));
    }

    @Test
    @Timeout(5)
    void renewalNoticesWhenTheLeaseWasTakenAwayAndDoesNotExtendTheNewOwner() throws Exception {
        ProjectWriteLeaseManager.Lease lease = manager(Duration.ofMillis(50), Duration.ofSeconds(1)).acquire("proj-1");
        Instant otherExpiry = START.plusSeconds(5);
        collection.put("proj-1", "new-owner", otherExpiry);

        awaitTrue(lease::isLost);

        assertEquals(otherExpiry, collection.get("proj-1").getExpiresAt());
        assertEquals(1.0, meters.counter(ProjectWriteLeaseManager.LOST_COUNTER).count());
        lease.release();
        assertEquals("new-owner", collection.get("proj-1").getOwnerToken());
    }

    @Test
    @Timeout(5)
    void aTransientStoreFailureDuringAcquireIsRetried() throws Exception {
        collection.upcomingFailures.add(new DataAccessResourceFailureException("connection reset"));

        ProjectWriteLeaseManager.Lease lease = manager(Duration.ofSeconds(10), Duration.ofSeconds(1)).acquire("proj-1");

        assertEquals(2, collection.insertAttempts.get());
        assertEquals(lease.getOwnerToken(), collection.get("proj-1").getOwnerToken());
        lease.release();
    }

    @Test
    @Timeout(5)
    void aStoreThatStaysDownSurfacesAsTheTimeoutWithTheCause() {
        ProjectWriteLeaseManager leases = manager(Duration.ofSeconds(10), Duration.ofMillis(150));
        for (int i = 0; i < 200; i++) {
            collection.upcomingFailures.add(new DataAccessResourceFailureException("down"));
        }

        ProjectWriteLeaseUnavailableException thrown =
                assertThrows(ProjectWriteLeaseUnavailableException.class, () -> leases.acquire("proj-1"));

        assertInstanceOf(DataAccessResourceFailureException.class, thrown.getCause());
    }

    @Test
    @Timeout(5)
    void aFailedReleaseDoesNotThrow() throws Exception {
        ProjectWriteLeaseManager.Lease lease = manager(Duration.ofSeconds(10), Duration.ofSeconds(1)).acquire("proj-1");
        collection.upcomingFailures.add(new DataAccessResourceFailureException("down"));

        lease.release();

        assertNotNull(collection.get("proj-1"));
    }

    @Test
    void settingsRejectARenewIntervalThatIsNotShorterThanTheTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> ProjectWriteLeaseManager.Settings.of(TTL, TTL, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectWriteLeaseManager.Settings.of(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectWriteLeaseManager.Settings.of(TTL, Duration.ofSeconds(1), Duration.ofSeconds(-1)));
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(10);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
