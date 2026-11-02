package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.common.admission.ReservationLedger;
import self.research.ontology.owlEditor.service.BusyException;
import self.research.ontology.owlEditor.service.EditorReasonerCacheService;
import self.research.ontology.owlEditor.service.FakeMemorySignals;
import self.research.ontology.owlEditor.service.HeavyWorkAdmission;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReasonerControllerAdmissionTest {

    private static final String RULES = "Prefix(:=<http://example.org/n#>)\n"
            + "Ontology(<http://example.org/n>\nDeclaration(Class(:A))\nDeclaration(Class(:B))\n"
            + "Declaration(NamedIndividual(:a))\nClassAssertion(:A :a)\n"
            + "DLSafeRule(Body(ClassAtom(:A Variable(:x))) Head(ClassAtom(:B Variable(:x))))\n)\n";

    private ReasonerController controller;
    private FakeMemorySignals signals;
    private ReservationLedger ledger;
    private final AtomicLong reservedDuringRun = new AtomicLong(-1);

    @BeforeEach
    void setUp() throws Exception {
        signals = new FakeMemorySignals();
        ledger = signals.ledger();
        controller = new ReasonerController();
        EditorReasonerCacheService cache = mock(EditorReasonerCacheService.class);
        OWLOntology ontology = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(
                new ByteArrayInputStream(RULES.getBytes(StandardCharsets.UTF_8)));
        when(cache.getOntology("p")).thenReturn(Optional.of(ontology));
        ReflectionTestUtils.setField(controller, "editorReasonerCache", cache);
        ReflectionTestUtils.setField(controller, "heavyWork",
                new HeavyWorkAdmission(ledger, new self.research.ontology.common.admission.Estimates()) {
                    @Override
                    public Hold admitRuleExecute(String userId, long triples) {
                        Hold inner = super.admitRuleExecute(userId, triples);
                        return new Hold() {
                            @Override
                            public void close() {
                                reservedDuringRun.set(ledger.reservedBytes());
                                inner.close();
                            }

                            @Override
                            public long bytes() {
                                return inner.bytes();
                            }
                        };
                    }
                });
    }

    @Test
    void executeHoldsAReservationWhileRunningAndReleasesItAfterwards() throws Exception {
        controller.useRuleStage((ontology, rules) -> Optional.of(Set.of()));
        controller.executeRules("p", null, false, new MockHttpServletRequest());
        assertTrue(reservedDuringRun.get() >= HeavyWorkAdmission.MIN_ESTIMATE_BYTES, "reserved " + reservedDuringRun);
        assertEquals(0, ledger.reservedBytes());
        assertEquals(0, ledger.capacityReport().totals().activeCount());
    }

    @Test
    void executeReleasesWhenTheRuleStageFails() throws Exception {
        controller.useRuleStage((ontology, rules) -> {
            throw new IllegalStateException("engine exploded");
        });
        controller.executeRules("p", null, false, new MockHttpServletRequest());
        assertEquals(0, ledger.reservedBytes());
        assertEquals(0, ledger.capacityReport().totals().activeCount());
    }

    @Test
    void executeIsRefusedAsBusyUnderMemoryPressureAndNeverRuns() {
        signals.afterGc = 900 * FakeMemorySignals.MIB;
        controller.useRuleStage((ontology, rules) -> {
            reservedDuringRun.set(-2);
            return Optional.of(Set.of());
        });
        BusyException busy = assertThrows(BusyException.class,
                () -> controller.executeRules("p", null, false, new MockHttpServletRequest()));
        assertEquals(self.research.ontology.common.admission.RefusalReason.HEAP_PRESSURE, busy.reason());
        assertEquals(-1, reservedDuringRun.get());
        assertEquals(0, ledger.reservedBytes());
    }
}
