package self.research.ontology.swrl.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.SWRLAtom;
import org.semanticweb.owlapi.model.SWRLRule;
import org.semanticweb.owlapi.model.SWRLVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferSummaryTest {

    private static final String NS = "http://example.org/bakery#";
    private static final OWLDataFactory F = OWLManager.getOWLDataFactory();
    private static final IRI SELECT = IRI.create("http://sqwrl.stanford.edu/ontologies/built-ins/3.4/sqwrl.owl#select");

    private final OntologyClientService client = Mockito.mock(OntologyClientService.class);
    private final SwrlEngineService service = new SwrlEngineService();

    TransferSummaryTest() throws Exception {
        ReflectionTestUtils.setField(service, "ontologyClient", client);
        ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 60);
        Mockito.when(client.maxAxioms()).thenReturn(1_000_000L);
        Mockito.when(client.fetchOntologyWithStamp(Mockito.anyString(), Mockito.any()))
                .thenAnswer(i -> new OntologyClientService.FetchedOntology(withRules(), 1L, null));
    }

    @AfterEach
    void clear() {
        OwnerContext.clear();
        service.shutdownGate();
    }

    private static SWRLRule rule(String label, SWRLAtom head, IRI flag) {
        SWRLVariable p = F.getSWRLVariable(IRI.create("urn:swrl#p"));
        Set<OWLAnnotation> annotations = new LinkedHashSet<>();
        annotations.add(F.getOWLAnnotation(F.getRDFSLabel(), F.getOWLLiteral(label)));
        if (flag != null) {
            annotations.add(F.getOWLAnnotation(F.getOWLAnnotationProperty(flag), F.getOWLLiteral(false)));
        }
        SWRLAtom conclusion = head != null ? head : F.getSWRLClassAtom(F.getOWLClass(IRI.create(NS + "Loaf")), p);
        return F.getSWRLRule(Set.of(F.getSWRLClassAtom(F.getOWLClass(IRI.create(NS + "Bread")), p)), Set.of(conclusion), annotations);
    }

    static OWLOntology withRules() throws Exception {
        OWLOntology o = AssertedAxiomListerTest.bakery();
        var m = o.getOWLOntologyManager();
        m.addAxiom(o, rule("Plain rule", null, null));
        m.addAxiom(o, rule("Second rule", F.getSWRLClassAtom(F.getOWLClass(IRI.create(NS + "Baker")),
                F.getSWRLVariable(IRI.create("urn:swrl#p"))), null));
        m.addAxiom(o, rule("Off rule", null, OntologyRules.ENABLED));
        m.addAxiom(o, rule("Protege off rule", null, OntologyRules.SWRLA_ENABLED));
        m.addAxiom(o, rule("Query rule", F.getSWRLBuiltInAtom(SELECT, List.of(F.getSWRLLiteralArgument(F.getOWLLiteral("x")))), null));
        return o;
    }

    @Test
    void theCountsMatchTheOntologyAndOnlyEnabledRulesAreCounted() throws Exception {
        TransferSummary summary = service.transferSummary("p1", null);

        assertEquals(2, summary.rulesExported());
        assertEquals(3, summary.classDeclarations());
        assertEquals(3, summary.individualDeclarations());
        assertEquals(1, summary.objectPropertyDeclarations());
        assertEquals(1, summary.dataPropertyDeclarations());
        assertEquals(AssertedAxiomListerTest.bakery().getAxiomCount(), summary.totalAxioms());
        assertTrue(summary.elapsedMs() >= 0);
    }

    @Test
    void queriesAndDisabledRulesAreListedAsSkippedWithAReason() throws Exception {
        TransferSummary summary = service.transferSummary("p1", null);

        assertEquals(3, summary.skippedRules().size());
        assertTrue(summary.skippedRules().stream().filter(s -> s.ruleName().equals("Query rule"))
                .allMatch(s -> s.reason().contains("SQWRL")));
        assertTrue(summary.skippedRules().stream().filter(s -> s.ruleName().endsWith("off rule") || s.ruleName().equals("Off rule"))
                .allMatch(s -> s.reason().contains("disabled")));
    }

    @Test
    void theDraftIsReadForTheNamedUserOnly() throws Exception {
        service.transferSummary("p1", "u1");

        Mockito.verify(client).fetchOntologyWithStamp("p1", "u1");
        Mockito.verify(client, Mockito.never()).fetchOntologyWithStamp("p1", null);
    }

    @Test
    void anUnchangedOntologyIsFetchedOnceAndSharedWithTheAssertedList() throws Exception {
        Mockito.when(client.currentStamp("p1", null)).thenReturn("r:1");

        service.transferSummary("p1", null);
        service.transferSummary("p1", null);
        service.listAssertedAxioms("p1", null, null, null, 0, 3);

        Mockito.verify(client, Mockito.times(1)).fetchOntologyWithStamp("p1", null);
    }

    @Test
    void aBusyGateRefusesWithTheSameBusyAnswerAsTheAssertedList() throws Exception {
        ReflectionTestUtils.setField(service, "validationWorkers", 1);
        ReflectionTestUtils.setField(service, "validationQueue", 1);
        ReflectionTestUtils.setField(service, "validationMaxWaitMs", 200L);
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                service.validationGate().call("someone-else", () -> {
                    inside.countDown();
                    release.await();
                    return null;
                }, 30);
            } catch (Exception ignored) {
            }
        });
        holder.start();
        inside.await();

        SwrlBusyException busy = assertThrows(SwrlBusyException.class, () -> service.transferSummary("p1", null));

        assertEquals(ExecutionGate.WAIT_TIMEOUT, busy.getReason());
        release.countDown();
        holder.join();
    }
}
