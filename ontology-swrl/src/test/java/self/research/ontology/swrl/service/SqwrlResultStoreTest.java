package self.research.ontology.swrl.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqwrlResultStoreTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

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
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    static final List<String> COLS = List.of("p");
    static final SqwrlResultStore.Meta META = new SqwrlResultStore.Meta("q", "x", 1, 3, true, false, 1000, false);

    static List<Map<String, String>> rows(int n, int width) {
        List<Map<String, String>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("p", "x".repeat(width));
            out.add(row);
        }
        return out;
    }

    @Test
    void anEntryExpiresAfterTheTtl() {
        MutableClock clock = new MutableClock();
        SqwrlResultStore store = new SqwrlResultStore(clock, Duration.ofMinutes(15), 5, 50, 1 << 20);
        String id = store.put("p1", "u1", COLS, rows(3, 4), META).get().id();
        clock.advance(Duration.ofMinutes(14));
        assertTrue(store.find(id, "p1", "u1").isPresent());
        clock.advance(Duration.ofMinutes(2));
        assertFalse(store.find(id, "p1", "u1").isPresent());
        assertEquals(0, store.size());
    }

    @Test
    void anotherOwnerOrProjectCannotReadAnEntry() {
        SqwrlResultStore store = new SqwrlResultStore(new MutableClock(), Duration.ofMinutes(15), 5, 50, 1 << 20);
        String id = store.put("p1", "u1", COLS, rows(1, 4), META).get().id();
        assertFalse(store.find(id, "p1", "u2").isPresent());
        assertFalse(store.find(id, "p2", "u1").isPresent());
        assertFalse(store.find(id, "p1", null).isPresent());
        assertTrue(store.find(id, "p1", "u1").isPresent());
    }

    @Test
    void theOldestEntryOfAnOwnerIsEvictedPastThePerOwnerCap() {
        MutableClock clock = new MutableClock();
        SqwrlResultStore store = new SqwrlResultStore(clock, Duration.ofMinutes(15), 2, 50, 1 << 20);
        String first = store.put("p1", "u1", COLS, rows(1, 4), META).get().id();
        clock.advance(Duration.ofSeconds(1));
        String second = store.put("p1", "u1", COLS, rows(1, 4), META).get().id();
        clock.advance(Duration.ofSeconds(1));
        String other = store.put("p1", "u2", COLS, rows(1, 4), META).get().id();
        clock.advance(Duration.ofSeconds(1));
        String third = store.put("p1", "u1", COLS, rows(1, 4), META).get().id();
        assertFalse(store.find(first, "p1", "u1").isPresent());
        assertTrue(store.find(second, "p1", "u1").isPresent());
        assertTrue(store.find(third, "p1", "u1").isPresent());
        assertTrue(store.find(other, "p1", "u2").isPresent());
    }

    @Test
    void theTotalCapEvictsTheOldest() {
        MutableClock clock = new MutableClock();
        SqwrlResultStore store = new SqwrlResultStore(clock, Duration.ofMinutes(15), 5, 3, 1 << 20);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(store.put("p1", "u" + i, COLS, rows(1, 4), META).get().id());
            clock.advance(Duration.ofSeconds(1));
        }
        assertEquals(3, store.size());
        assertFalse(store.find(ids.get(0), "p1", "u0").isPresent());
        assertTrue(store.find(ids.get(4), "p1", "u4").isPresent());
    }

    @Test
    void theByteBudgetEvictsOldAndRefusesAnOversizedResult() {
        MutableClock clock = new MutableClock();
        long one = SqwrlResultStore.estimateBytes(COLS, rows(10, 100));
        SqwrlResultStore store = new SqwrlResultStore(clock, Duration.ofMinutes(15), 5, 50, one * 2);
        String a = store.put("p1", "u1", COLS, rows(10, 100), META).get().id();
        clock.advance(Duration.ofSeconds(1));
        String b = store.put("p1", "u2", COLS, rows(10, 100), META).get().id();
        clock.advance(Duration.ofSeconds(1));
        String c = store.put("p1", "u3", COLS, rows(10, 100), META).get().id();
        assertFalse(store.find(a, "p1", "u1").isPresent());
        assertTrue(store.find(b, "p1", "u2").isPresent());
        assertTrue(store.find(c, "p1", "u3").isPresent());
        assertTrue(store.storedBytes() <= one * 2);
        assertTrue(store.put("p1", "u4", COLS, rows(10, 1000), META).isEmpty());
        assertEquals(2, store.size());
    }
}
