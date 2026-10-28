package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.impl.MapBindingSet;
import org.junit.jupiter.api.Test;
import self.research.ontology.common.admission.ReservationLedger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparqlRunStoreTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final List<String> VARS = List.of("s", "v");

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

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

    private static List<BindingSet> rows(int count) {
        List<BindingSet> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MapBindingSet row = new MapBindingSet();
            row.addBinding("s", VF.createIRI("http://example.org/s" + i));
            row.addBinding("v", VF.createLiteral(i));
            rows.add(row);
        }
        return rows;
    }

    private static SparqlRunStore store(MutableClock clock, int perOwner, int total, long bytes, int runRows, long runBytes) {
        return new SparqlRunStore(clock, Duration.ofMinutes(15), perOwner, total, bytes, runRows, runBytes);
    }

    @Test
    void cellsRoundTripEveryTermKind() {
        Value[] values = {
                VF.createIRI("http://example.org/a"),
                VF.createBNode("n1"),
                VF.createLiteral("plain"),
                VF.createLiteral("hola", "es"),
                VF.createLiteral("5", XSD.INTEGER),
                VF.createLiteral("")
        };
        for (Value value : values) {
            assertEquals(value, SparqlRunStore.decode(SparqlRunStore.encode(value)));
        }
        assertNull(SparqlRunStore.encode(null));
        assertNull(SparqlRunStore.decode(null));
        assertEquals("hola", SparqlRunStore.display(SparqlRunStore.encode(values[3])));
        assertEquals("5", SparqlRunStore.display(SparqlRunStore.encode(values[4])));
        assertEquals("_:n1", SparqlRunStore.display(SparqlRunStore.encode(values[1])));
        assertEquals("", SparqlRunStore.display(SparqlRunStore.encode(values[5])));
    }

    @Test
    void keepsOnlyTheRowCapAndCountsPastIt() {
        SparqlRunStore store = store(new MutableClock(), 5, 50, 64L << 20, 100, 16L << 20);
        SparqlRunStore.Entry entry = store.put("p", "u", VARS, rows(100), 250, true, 5);
        assertEquals(100, entry.rows().length);
        assertEquals(250, entry.meta().totalRows());
        assertFalse(entry.meta().totalRowsExact());
        assertTrue(entry.meta().truncated());
        assertEquals(100, entry.meta().rowLimit());
        assertFalse(entry.meta().bytesCapped());
    }

    @Test
    void anExactResultThatFitsIsNotTruncated() {
        SparqlRunStore store = store(new MutableClock(), 5, 50, 64L << 20, 1000, 16L << 20);
        SparqlRunStore.Entry entry = store.put("p", "u", VARS, rows(30), 30, false, 5);
        assertEquals(30, entry.rows().length);
        assertTrue(entry.meta().totalRowsExact());
        assertFalse(entry.meta().truncated());
    }

    @Test
    void theRowCapAppliesEvenWhenMoreRowsAreHandedIn() {
        SparqlRunStore store = store(new MutableClock(), 5, 50, 64L << 20, 40, 16L << 20);
        SparqlRunStore.Entry entry = store.put("p", "u", VARS, rows(100), 100, false, 5);
        assertEquals(40, entry.rows().length);
        assertEquals(100, entry.meta().totalRows());
        assertTrue(entry.meta().truncated());
    }

    @Test
    void theByteCapStopsStoringAndSaysSo() {
        SparqlRunStore store = store(new MutableClock(), 5, 50, 64L << 20, 100_000, 5_000);
        SparqlRunStore.Entry entry = store.put("p", "u", VARS, rows(1000), 1000, false, 5);
        assertTrue(entry.rows().length > 0 && entry.rows().length < 1000, "rows " + entry.rows().length);
        assertTrue(entry.estimatedBytes() <= 5_000 + 400);
        assertTrue(entry.meta().truncated());
        assertTrue(entry.meta().bytesCapped());
    }

    @Test
    void anotherOwnerOrProjectCannotReadARun() {
        SparqlRunStore store = store(new MutableClock(), 5, 50, 64L << 20, 100, 16L << 20);
        SparqlRunStore.Entry entry = store.put("p1", "u1", VARS, rows(3), 3, false, 5);
        assertTrue(store.find(entry.id(), "p1", "u1").isPresent());
        assertTrue(store.find(entry.id(), "p2", "u1").isEmpty());
        assertTrue(store.find(entry.id(), "p1", "u2").isEmpty());
        assertTrue(store.find("unknown", "p1", "u1").isEmpty());
        assertTrue(store.find(null, "p1", "u1").isEmpty());
    }

    @Test
    void aRunExpiresAfterTheTimeToLive() {
        MutableClock clock = new MutableClock();
        SparqlRunStore store = store(clock, 5, 50, 64L << 20, 100, 16L << 20);
        SparqlRunStore.Entry entry = store.put("p", "u", VARS, rows(3), 3, false, 5);
        clock.now = clock.now.plus(Duration.ofMinutes(14));
        assertTrue(store.find(entry.id(), "p", "u").isPresent());
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertTrue(store.find(entry.id(), "p", "u").isEmpty());
        assertEquals(0, store.size());
    }

    @Test
    void thePerOwnerLimitEvictsTheOldestRun() {
        MutableClock clock = new MutableClock();
        SparqlRunStore store = store(clock, 5, 50, 64L << 20, 100, 16L << 20);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            clock.now = clock.now.plusSeconds(1);
            ids.add(store.put("p", "u", VARS, rows(3), 3, false, 5).id());
        }
        assertEquals(5, store.size());
        assertTrue(store.find(ids.get(0), "p", "u").isEmpty());
        assertTrue(store.find(ids.get(5), "p", "u").isPresent());
    }

    @Test
    void theTotalLimitEvictsTheOldestRunAcrossOwners() {
        MutableClock clock = new MutableClock();
        SparqlRunStore store = store(clock, 5, 3, 64L << 20, 100, 16L << 20);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            clock.now = clock.now.plusSeconds(1);
            ids.add(store.put("p", "u" + i, VARS, rows(3), 3, false, 5).id());
        }
        assertEquals(3, store.size());
        assertTrue(store.find(ids.get(0), "p", "u0").isEmpty());
    }

    @Test
    void theTotalByteBudgetEvictsOldRunsAndIsNeverExceeded() {
        MutableClock clock = new MutableClock();
        long budget = 40_000;
        SparqlRunStore store = store(clock, 50, 50, budget, 1000, budget);
        String first = null;
        for (int i = 0; i < 20; i++) {
            clock.now = clock.now.plusSeconds(1);
            SparqlRunStore.Entry entry = store.put("p", "u" + i, VARS, rows(100), 100, false, 5);
            if (first == null) {
                first = entry.id();
            }
            assertTrue(store.storedBytes() <= budget, "stored " + store.storedBytes());
        }
        assertTrue(store.find(first, "p", "u0").isEmpty());
        assertTrue(store.size() < 20);
    }

    @Test
    void storedBytesAreCountedOnTheLedgerAccount() {
        ReservationLedger ledger = new ReservationLedger(new self.research.ontology.common.admission.CgroupAwareMemorySignals(),
                self.research.ontology.common.admission.CapacityConfig.defaults(), null, null);
        SparqlRunStore store = store(new MutableClock(), 5, 50, 64L << 20, 100, 16L << 20);
        store.setCapacityLedger(ledger);
        SparqlRunStore.Entry entry = store.put("p", "u", VARS, rows(50), 50, false, 5);
        assertEquals((Long) entry.estimatedBytes(), ledger.capacityReport().cacheAccounts().get("sparqlRuns"));
    }

    @Test
    void retentionReflectsTheConfiguredCaps() {
        SparqlRunStore store = store(new MutableClock(), 5, 50, 64L << 20, 321, 1_000_000);
        assertEquals(321, store.retention().maxRows());
        assertEquals(1_000_000, store.retention().maxBytes());
        Optional<SparqlRunStore.Entry> none = store.find("x", "p", "u");
        assertTrue(none.isEmpty());
    }
}
