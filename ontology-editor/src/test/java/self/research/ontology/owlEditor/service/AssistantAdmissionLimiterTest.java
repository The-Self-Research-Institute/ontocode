package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.owlEditor.service.AssistantAdmissionLimiter.Admitted;
import self.research.ontology.owlEditor.service.AssistantAdmissionLimiter.Rejected;
import self.research.ontology.owlEditor.service.AssistantAdmissionLimiter.ToolAdmission;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantAdmissionLimiterTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void perUserLimitRejectsTheFifthConcurrentCallAndAdmitsAgainAfterRelease() {
        AssistantAdmissionLimiter limiter = new AssistantAdmissionLimiter(4, 8, 32, 30, 1, Clock.systemUTC());
        List<Admitted> held = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            held.add(assertInstanceOf(Admitted.class, limiter.tryAcquireTool("u", "p" + i)));
        }

        Rejected rejected = assertInstanceOf(Rejected.class, limiter.tryAcquireTool("u", "p9"));
        assertEquals("user", rejected.limit());
        assertEquals(1, rejected.retryAfterSeconds());
        assertInstanceOf(Admitted.class, limiter.tryAcquireTool("someone-else", "p9")).close();

        held.get(0).close();
        assertInstanceOf(Admitted.class, limiter.tryAcquireTool("u", "p9")).close();
        held.forEach(Admitted::close);
        assertEquals(0, limiter.inFlightForUser("u"));
    }

    @Test
    void perProjectLimitAppliesAcrossUsersAndRollsBackTheUserSlot() {
        AssistantAdmissionLimiter limiter = new AssistantAdmissionLimiter(4, 8, 32, 30, 1, Clock.systemUTC());
        List<Admitted> held = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            held.add(assertInstanceOf(Admitted.class, limiter.tryAcquireTool("user" + i, "proj")));
        }

        Rejected rejected = assertInstanceOf(Rejected.class, limiter.tryAcquireTool("late", "proj"));
        assertEquals("project", rejected.limit());
        assertEquals(0, limiter.inFlightForUser("late"));
        held.forEach(Admitted::close);
        assertEquals(0, limiter.inFlightForProject("proj"));
    }

    @Test
    void globalLimitRollsBackUserAndProjectSlots() {
        AssistantAdmissionLimiter limiter = new AssistantAdmissionLimiter(4, 8, 3, 30, 2, Clock.systemUTC());
        List<Admitted> held = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            held.add(assertInstanceOf(Admitted.class, limiter.tryAcquireTool("user" + i, "proj" + i)));
        }

        Rejected rejected = assertInstanceOf(Rejected.class, limiter.tryAcquireTool("late", "late-proj"));
        assertEquals("global", rejected.limit());
        assertEquals(2, rejected.retryAfterSeconds());
        assertEquals(0, limiter.inFlightForUser("late"));
        assertEquals(0, limiter.inFlightForProject("late-proj"));
        assertEquals(3, limiter.inFlightGlobal());
        held.forEach(Admitted::close);
        assertEquals(0, limiter.inFlightGlobal());
    }

    @Test
    void closingTwiceReleasesOnlyOnce() {
        AssistantAdmissionLimiter limiter = new AssistantAdmissionLimiter(4, 8, 32, 30, 1, Clock.systemUTC());
        Admitted first = assertInstanceOf(Admitted.class, limiter.tryAcquireTool("u", "p"));
        Admitted second = assertInstanceOf(Admitted.class, limiter.tryAcquireTool("u", "p"));

        first.close();
        first.close();

        assertEquals(1, limiter.inFlightForUser("u"));
        second.close();
        assertEquals(0, limiter.inFlightForUser("u"));
    }

    @Test
    void concurrentCallersNeverExceedTheLimit() throws Exception {
        AssistantAdmissionLimiter limiter = new AssistantAdmissionLimiter(4, 100, 100, 30, 1, Clock.systemUTC());
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        for (int t = 0; t < 16; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < 500; i++) {
                    ToolAdmission admission = limiter.tryAcquireTool("u", "p");
                    if (admission instanceof Admitted permit) {
                        int now = current.incrementAndGet();
                        peak.accumulateAndGet(now, Math::max);
                        current.decrementAndGet();
                        permit.close();
                    } else {
                        rejections.incrementAndGet();
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertTrue(peak.get() <= 4, "peak " + peak.get());
        assertEquals(0, limiter.inFlightForUser("u"));
        assertEquals(0, limiter.inFlightGlobal());
    }

    @Test
    void sessionCreatesAreLimitedPerUserPerSlidingMinute() {
        MutableClock clock = new MutableClock();
        AssistantAdmissionLimiter limiter = new AssistantAdmissionLimiter(4, 8, 32, 3, 1, clock);

        assertTrue(limiter.tryAdmitSessionCreate("u").isEmpty());
        clock.advance(Duration.ofSeconds(20));
        assertTrue(limiter.tryAdmitSessionCreate("u").isEmpty());
        assertTrue(limiter.tryAdmitSessionCreate("u").isEmpty());

        Optional<Integer> rejected = limiter.tryAdmitSessionCreate("u");
        assertEquals(Optional.of(40), rejected);
        assertTrue(limiter.tryAdmitSessionCreate("other").isEmpty());

        clock.advance(Duration.ofSeconds(40));
        assertTrue(limiter.tryAdmitSessionCreate("u").isEmpty());
        assertEquals(Optional.of(20), limiter.tryAdmitSessionCreate("u"));
    }
}
