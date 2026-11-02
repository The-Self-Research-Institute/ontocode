package self.research.ontology.plugins.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.common.PreparedOntology;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ReasonerServiceRuleCacheTest {

    private OWLOntology ontology;
    private AtomicInteger prepareCalls;
    private boolean complete;
    private ReasonerService service;

    @BeforeEach
    void setUp() throws OWLOntologyCreationException {
        ontology = OWLManager.createOWLOntologyManager().createOntology();
        prepareCalls = new AtomicInteger();
        complete = true;
        service = new ReasonerService() {
            @Override
            PreparedOntology prepareRules(OWLOntology input, ReasonerType type) {
                prepareCalls.incrementAndGet();
                return complete
                        ? new PreparedOntology(input, 0, 0, 0, null)
                        : new PreparedOntology(input, 0, 0, 1, "rule engine down");
            }
        };
        ReflectionTestUtils.setField(service, "reasonerOperationTimeoutMs", 30_000L);
    }

    @Test
    void preparesRulesOncePerBuild() {
        service.getReasoner(ontology, ReasonerType.HERMIT);
        service.getReasoner(ontology, ReasonerType.HERMIT);

        assertEquals(1, prepareCalls.get());
    }

    @Test
    void completePreparationIsCached() {
        OWLReasoner first = service.getReasoner(ontology, ReasonerType.HERMIT);
        OWLReasoner second = service.getReasoner(ontology, ReasonerType.HERMIT);

        assertSame(first, second);
    }

    @Test
    void partialPreparationIsNotCached() {
        complete = false;
        OWLReasoner first = service.getReasoner(ontology, ReasonerType.HERMIT);
        complete = true;
        OWLReasoner second = service.getReasoner(ontology, ReasonerType.HERMIT);
        OWLReasoner third = service.getReasoner(ontology, ReasonerType.HERMIT);

        assertNotSame(first, second);
        assertSame(second, third);
        assertEquals(2, prepareCalls.get());
    }

    @Test
    void noteIsPresentWhenPartial() {
        complete = false;
        service.beginRuleScope();
        try {
            service.getReasoner(ontology, ReasonerType.HERMIT);
            assertEquals("rule engine down", service.ruleNote());
        } finally {
            service.endRuleScope();
        }
    }

    @Test
    void noteIsAbsentWhenComplete() {
        service.beginRuleScope();
        try {
            service.getReasoner(ontology, ReasonerType.HERMIT);
            assertNull(service.ruleNote());
        } finally {
            service.endRuleScope();
        }
    }

    @Test
    void partialIsPreparedOnlyOnceWithinOneRequest() {
        complete = false;
        service.beginRuleScope();
        try {
            OWLReasoner first = service.getReasoner(ontology, ReasonerType.HERMIT);
            OWLReasoner second = service.getReasoner(ontology, ReasonerType.HERMIT);

            assertSame(first, second);
            assertEquals(1, prepareCalls.get());
            assertEquals("rule engine down", service.ruleNote());
        } finally {
            service.endRuleScope();
        }
    }

    @Test
    void partialIsPreparedAgainOnNextRequest() {
        complete = false;
        service.beginRuleScope();
        service.getReasoner(ontology, ReasonerType.HERMIT);
        service.endRuleScope();
        service.beginRuleScope();
        try {
            service.getReasoner(ontology, ReasonerType.HERMIT);
        } finally {
            service.endRuleScope();
        }

        assertEquals(2, prepareCalls.get());
    }
}
