package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import self.research.ontology.common.admission.RefusalReason;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExplainInferenceLimiterTest {

    private static final class MovableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-30T10:00:00Z"));

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        void advance(Duration by) {
            now.set(now.get().plus(by));
        }
    }

    @Test
    void refusesOnceTheUserHasTheMaximumRunning() {
        ExplainInferenceLimiter limiter = new ExplainInferenceLimiter(2);
        limiter.started("u1", "a");
        limiter.started("u1", "b");

        BusyException busy = assertThrows(BusyException.class, () -> limiter.requireRoom("u1"));

        assertEquals(RefusalReason.OWNER_LIMIT, busy.reason());
        assertEquals(5, busy.retryAfterSeconds());
        assertDoesNotThrow(() -> limiter.requireRoom("u2"));
    }

    @Test
    void finishingAJobMakesRoomAgain() {
        ExplainInferenceLimiter limiter = new ExplainInferenceLimiter(1);
        limiter.started("u1", "a");
        limiter.finished("u1", "a");

        assertDoesNotThrow(() -> limiter.requireRoom("u1"));
        assertEquals(0, limiter.active("u1"));
    }

    @Test
    void aJobNobodyCameBackForStopsCountingAfterTheHoldLimit() {
        MovableClock clock = new MovableClock();
        ExplainInferenceLimiter limiter = new ExplainInferenceLimiter(clock, 1);
        limiter.started("u1", "a");
        assertThrows(BusyException.class, () -> limiter.requireRoom("u1"));

        clock.advance(ExplainInferenceLimiter.HOLD_LIMIT.plusSeconds(1));

        assertDoesNotThrow(() -> limiter.requireRoom("u1"));
        assertEquals(0, limiter.active("u1"));
    }

    @Test
    void zeroMeansNoLimit() {
        ExplainInferenceLimiter limiter = new ExplainInferenceLimiter(0);
        for (int i = 0; i < 10; i++) {
            limiter.started("u1", "j" + i);
        }

        assertDoesNotThrow(() -> limiter.requireRoom("u1"));
    }
}
