package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.common.admission.Estimates;
import self.research.ontology.common.admission.RefusalReason;
import self.research.ontology.common.admission.ReservationLedger;
import self.research.ontology.common.admission.WorkKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeavyWorkAdmissionTest {

    private static final long MIB = FakeMemorySignals.MIB;

    private FakeMemorySignals signals;
    private ReservationLedger ledger;
    private HeavyWorkAdmission admission;

    @BeforeEach
    void setUp() {
        signals = new FakeMemorySignals();
        ledger = signals.ledger();
        admission = new HeavyWorkAdmission(ledger, new Estimates());
    }

    @Test
    void admitHoldsBytesUntilClosedAndClosingTwiceIsHarmless() {
        HeavyWorkAdmission.Hold hold = admission.admit("u1", WorkKind.REASONING, 50 * MIB);
        assertEquals(50 * MIB, ledger.reservedBytes());
        hold.close();
        hold.close();
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void runReleasesWhenTheWorkThrows() {
        assertThrows(IllegalStateException.class, () -> admission.run("u1", WorkKind.EXECUTE_RULES, 20 * MIB, () -> {
            assertEquals(20 * MIB, ledger.reservedBytes());
            throw new IllegalStateException("boom");
        }));
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void runReturnsTheResult() throws Exception {
        assertEquals("done", admission.run("u1", WorkKind.REASONING, MIB, () -> "done"));
    }

    @Test
    void tinyEstimatesAreRaisedToTheMinimum() {
        try (HeavyWorkAdmission.Hold hold = admission.admit("u1", WorkKind.REASONING, 0)) {
            assertEquals(HeavyWorkAdmission.MIN_ESTIMATE_BYTES, hold.bytes());
        }
    }

    @Test
    void heapPressureRefuses() {
        signals.afterGc = 900 * MIB;
        BusyException busy = assertThrows(BusyException.class, () -> admission.admit("u1", WorkKind.REASONING, 10 * MIB));
        assertEquals(RefusalReason.HEAP_PRESSURE, busy.reason());
        assertTrue(busy.retryAfterSeconds() > 0);
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void containerPressureRefuses() {
        signals.limit = self.research.ontology.common.admission.MemorySignals.Metric.available(1000 * MIB);
        signals.usage = self.research.ontology.common.admission.MemorySignals.Metric.available(900 * MIB);
        BusyException busy = assertThrows(BusyException.class, () -> admission.admit("u1", WorkKind.DL_QUERY, 10 * MIB));
        assertEquals(RefusalReason.CONTAINER_PRESSURE, busy.reason());
    }

    @Test
    void anEstimateBiggerThanTheKindShareIsTooLarge() {
        BusyException busy = assertThrows(BusyException.class,
                () -> admission.admit("u1", WorkKind.EXECUTE_RULES, 400 * MIB));
        assertEquals(RefusalReason.TOO_LARGE, busy.reason());
        assertEquals(0, busy.retryAfterSeconds());
    }

    @Test
    void theSameOwnerCannotHoldTwoOfTheSameKind() {
        try (HeavyWorkAdmission.Hold first = admission.admit("u1", WorkKind.SPARQL_QUERY, 10 * MIB)) {
            try (HeavyWorkAdmission.Hold second = admission.admit("u1", WorkKind.SPARQL_QUERY, 10 * MIB)) {
                assertEquals(20 * MIB, ledger.reservedBytes());
            }
        }
        try (HeavyWorkAdmission.Hold first = admission.admit("u1", WorkKind.REASONING, 10 * MIB)) {
            BusyException busy = assertThrows(BusyException.class,
                    () -> admission.admit("u1", WorkKind.REASONING, 10 * MIB));
            assertTrue(busy.reason() == RefusalReason.OWNER_LIMIT || busy.reason() == RefusalReason.WAIT_TIMEOUT,
                    busy.reason().name());
        }
        assertEquals(0, ledger.reservedBytes());
    }

    @Test
    void aDisabledAdmissionAlwaysAdmitsAndHoldsNothing() {
        HeavyWorkAdmission off = HeavyWorkAdmission.disabled();
        assertFalse(off.enabled());
        try (HeavyWorkAdmission.Hold hold = off.admit("u1", WorkKind.REASONING, Long.MAX_VALUE)) {
            assertEquals(0, hold.bytes());
        }
    }

    @Test
    void anonymousCallersGetAFixedOwnerKey() {
        assertEquals("anonymous", HeavyWorkAdmission.ownerKey(null));
        assertEquals("anonymous", HeavyWorkAdmission.ownerKey("  "));
        assertEquals("user-7", HeavyWorkAdmission.ownerKey("user-7"));
    }

    @Test
    void estimatesComeFromTheSharedConstants() {
        try (HeavyWorkAdmission.Hold hold = admission.admitRuleExecute("u1", 1000)) {
            assertEquals(1000 * Estimates.DEFAULT_ESTIMATED_BYTES_PER_TRIPLE_RULE_EXECUTE <= HeavyWorkAdmission.MIN_ESTIMATE_BYTES
                    ? HeavyWorkAdmission.MIN_ESTIMATE_BYTES
                    : 1000 * Estimates.DEFAULT_ESTIMATED_BYTES_PER_TRIPLE_RULE_EXECUTE, hold.bytes());
        }
    }
}
