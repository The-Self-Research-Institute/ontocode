package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantProviderRateLimiterTest {

    private static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(1_000_000L);

        void advance(Duration d) {
            millis.addAndGet(d.toMillis());
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
        public long millis() {
            return millis.get();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    @Test
    void deniesAfterLimitAndReportsSecondsUntilWindowEnds() {
        MutableClock clock = new MutableClock();
        AssistantProviderRateLimiter limiter = new AssistantProviderRateLimiter(2, Duration.ofSeconds(60), 100, clock);

        assertTrue(limiter.tryAcquire("u").allowed());
        clock.advance(Duration.ofSeconds(10));
        assertTrue(limiter.tryAcquire("u").allowed());
        AssistantProviderRateLimiter.Decision denied = limiter.tryAcquire("u");

        assertFalse(denied.allowed());
        assertEquals(50, denied.retryAfterSeconds());
    }

    @Test
    void windowResetsAfterItElapses() {
        MutableClock clock = new MutableClock();
        AssistantProviderRateLimiter limiter = new AssistantProviderRateLimiter(1, Duration.ofSeconds(60), 100, clock);

        assertTrue(limiter.tryAcquire("u").allowed());
        assertFalse(limiter.tryAcquire("u").allowed());
        clock.advance(Duration.ofSeconds(60));

        assertTrue(limiter.tryAcquire("u").allowed());
    }

    @Test
    void keysAreIndependentAndCaseInsensitive() {
        MutableClock clock = new MutableClock();
        AssistantProviderRateLimiter limiter = new AssistantProviderRateLimiter(1, Duration.ofSeconds(60), 100, clock);

        assertTrue(limiter.tryAcquire("Alice@Example.com").allowed());
        assertFalse(limiter.tryAcquire("alice@example.com").allowed());
        assertTrue(limiter.tryAcquire("bob@example.com").allowed());
    }

    @Test
    void expiredEntriesAreSweptOnceThresholdIsReached() {
        MutableClock clock = new MutableClock();
        AssistantProviderRateLimiter limiter = new AssistantProviderRateLimiter(5, Duration.ofSeconds(60), 3, clock);

        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        limiter.tryAcquire("c");
        assertEquals(3, limiter.trackedKeys());
        clock.advance(Duration.ofSeconds(61));
        limiter.tryAcquire("d");

        assertEquals(1, limiter.trackedKeys());
    }

    @Test
    void concurrentCallersNeverExceedTheLimit() throws Exception {
        AssistantProviderRateLimiter limiter = new AssistantProviderRateLimiter(10, Duration.ofMinutes(5), 100,
                Clock.systemUTC());
        AtomicLong allowed = new AtomicLong();
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 20; i++) {
                    if (limiter.tryAcquire("shared").allowed()) {
                        allowed.incrementAndGet();
                    }
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(10, allowed.get());
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new AssistantProviderRateLimiter(0, Duration.ofSeconds(1), 1, Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class,
                () -> new AssistantProviderRateLimiter(1, Duration.ZERO, 1, Clock.systemUTC()));
    }
}
