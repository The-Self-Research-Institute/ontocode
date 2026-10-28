package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobOwnerRegistryTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-28T10:00:00Z");

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

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    @Test
    void remembersProjectAndOwners() {
        JobOwnerRegistry registry = new JobOwnerRegistry();
        registry.register("j1", "p1", "u-ann");

        JobOwnerRegistry.Entry entry = registry.find("j1").orElseThrow();
        assertEquals("p1", entry.projectId());
        assertEquals(java.util.Set.of("u-ann"), entry.owners());
        assertEquals(Optional.empty(), registry.find("other"));
    }

    @Test
    void jobSharedByTwoSubmittersHasBothOwnersAndKeepsItsFirstProject() {
        JobOwnerRegistry registry = new JobOwnerRegistry();
        registry.register("j1", "p1", "u-ann");
        registry.register("j1", "p9", "u-bob");

        JobOwnerRegistry.Entry entry = registry.find("j1").orElseThrow();
        assertEquals("p1", entry.projectId());
        assertEquals(java.util.Set.of("u-ann", "u-bob"), entry.owners());
    }

    @Test
    void anonymousSubmissionLeavesTheOwnerSetEmpty() {
        JobOwnerRegistry registry = new JobOwnerRegistry();
        registry.register("j1", "p1", null);

        assertTrue(registry.find("j1").orElseThrow().owners().isEmpty());
    }

    @Test
    void entriesExpireAfterTheTtl() {
        MutableClock clock = new MutableClock();
        JobOwnerRegistry registry = new JobOwnerRegistry(clock, Duration.ofHours(1), 100);
        registry.register("j1", "p1", "u-ann");

        clock.advance(Duration.ofMinutes(59));
        assertTrue(registry.find("j1").isPresent());
        clock.advance(Duration.ofMinutes(2));
        assertTrue(registry.find("j1").isEmpty());
    }

    @Test
    void oldestEntriesAreDroppedBeyondTheBound() {
        JobOwnerRegistry registry = new JobOwnerRegistry(Clock.systemUTC(), Duration.ofHours(1), 2);
        registry.register("a", "p", "u");
        registry.register("b", "p", "u");
        registry.register("c", "p", "u");

        assertTrue(registry.find("a").isEmpty());
        assertTrue(registry.find("b").isPresent());
        assertTrue(registry.find("c").isPresent());
    }
}
