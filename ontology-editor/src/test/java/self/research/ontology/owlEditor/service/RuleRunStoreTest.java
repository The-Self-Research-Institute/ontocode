package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleRunStoreTest {

    static final OWLDataFactory DF = OWLManager.getOWLDataFactory();

    static final class FakeClock extends Clock {
        Instant now = Instant.parse("2026-10-28T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
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

    static List<OWLAxiom> assertions(int n) {
        List<OWLAxiom> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(DF.getOWLClassAssertionAxiom(DF.getOWLClass(IRI.create("http://e.org/C")),
                    DF.getOWLNamedIndividual(IRI.create("http://e.org/i" + i))));
        }
        return out;
    }

    @Test
    void runExpiresAfterTtl() {
        FakeClock clock = new FakeClock();
        RuleRunStore store = new RuleRunStore(clock, Duration.ofMinutes(15), 5, 50, 100, 100);
        String id = store.put("p", "u", false, assertions(3), List.of());
        clock.advance(Duration.ofMinutes(14));
        assertTrue(store.find(id).isPresent());
        clock.advance(Duration.ofMinutes(2));
        assertFalse(store.find(id).isPresent());
        assertEquals(0, store.size());
    }

    @Test
    void perUserCapEvictsOnlyThatUsersOldest() {
        FakeClock clock = new FakeClock();
        RuleRunStore store = new RuleRunStore(clock, Duration.ofMinutes(15), 2, 50, 100, 100);
        String other = store.put("p", "bob", false, assertions(1), List.of());
        String first = store.put("p", "ann", false, assertions(1), List.of());
        clock.advance(Duration.ofSeconds(1));
        String second = store.put("p", "ann", false, assertions(1), List.of());
        clock.advance(Duration.ofSeconds(1));
        String third = store.put("p", "ann", false, assertions(1), List.of());
        assertFalse(store.find(first).isPresent());
        assertTrue(store.find(second).isPresent());
        assertTrue(store.find(third).isPresent());
        assertTrue(store.find(other).isPresent());
    }

    @Test
    void totalCapEvictsOldestOverall() {
        FakeClock clock = new FakeClock();
        RuleRunStore store = new RuleRunStore(clock, Duration.ofMinutes(15), 10, 3, 100, 100);
        String a = store.put("p", "u1", false, assertions(1), List.of());
        clock.advance(Duration.ofSeconds(1));
        store.put("p", "u2", false, assertions(1), List.of());
        clock.advance(Duration.ofSeconds(1));
        store.put("p", "u3", false, assertions(1), List.of());
        clock.advance(Duration.ofSeconds(1));
        store.put("p", "u4", false, assertions(1), List.of());
        assertEquals(3, store.size());
        assertFalse(store.find(a).isPresent());
    }

    @Test
    void theTotalAxiomBudgetEvictsTheOldestRunsFirst() {
        FakeClock clock = new FakeClock();
        RuleRunStore store = new RuleRunStore(clock, Duration.ofMinutes(15), 10, 50, 100, 100, 25);
        String a = store.put("p", "u1", false, assertions(10), List.of());
        clock.advance(Duration.ofSeconds(1));
        String b = store.put("p", "u2", false, assertions(10), List.of());
        clock.advance(Duration.ofSeconds(1));
        String c = store.put("p", "u3", false, assertions(10), List.of());
        assertFalse(store.find(a).isPresent());
        assertTrue(store.find(b).isPresent());
        assertTrue(store.find(c).isPresent());
        assertEquals(20, store.storedAxioms());
    }

    @Test
    void aSingleRunLargerThanTheBudgetIsTrimmedAndFlagged() {
        RuleRunStore store = new RuleRunStore(new FakeClock(), Duration.ofMinutes(15), 5, 50, 100, 100, 12);
        String id = store.put("p", "u", false, assertions(10), assertions(8));
        RuleRunStore.Run run = store.find(id).orElseThrow();
        assertEquals(10, run.conclusions().size());
        assertEquals(2, run.structure().size());
        assertTrue(run.capped());
        assertEquals(12, store.storedAxioms());
    }

    @Test
    void listsAreCappedAndFlagged() {
        RuleRunStore store = new RuleRunStore(new FakeClock(), Duration.ofMinutes(15), 5, 50, 10, 4);
        String id = store.put("p", "u", false, assertions(25), assertions(9));
        RuleRunStore.Run run = store.find(id).orElseThrow();
        assertEquals(10, run.conclusions().size());
        assertEquals(4, run.structure().size());
        assertTrue(run.capped());
        String small = store.put("p", "u", false, assertions(2), List.of());
        assertFalse(store.find(small).orElseThrow().capped());
    }

    @Test
    void concurrentPutsStayWithinTheBounds() throws Exception {
        RuleRunStore store = new RuleRunStore(Clock.systemUTC(), Duration.ofMinutes(15), 5, 20, 100, 100);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            String user = "u" + t;
            tasks.add(pool.submit(() -> {
                for (int i = 0; i < 50; i++) {
                    String id = store.put("p", user, false, assertions(3), List.of());
                    store.find(id);
                }
            }));
        }
        for (java.util.concurrent.Future<?> task : tasks) {
            task.get();
        }
        pool.shutdown();
        assertTrue(store.size() <= 20);
    }

    @Test
    void unknownAndNullIdsAreEmpty() {
        RuleRunStore store = new RuleRunStore(new FakeClock(), Duration.ofMinutes(15), 5, 50, 100, 100);
        assertFalse(store.find("nope").isPresent());
        assertFalse(store.find(null).isPresent());
    }
}
