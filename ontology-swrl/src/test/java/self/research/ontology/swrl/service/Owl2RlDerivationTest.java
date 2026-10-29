package self.research.ontology.swrl.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.swrl.model.InferredAxiom;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Owl2RlDerivationTest {

    private static final String NS = "http://example.org/garage#";
    private static final String PROJECT = "garage";
    private static final String OWNER = "owner-1";

    private final OntologyClientService client = Mockito.mock(OntologyClientService.class);
    private final SwrlEngineService service = new SwrlEngineService();
    private final ExecutorService blockers = Executors.newCachedThreadPool();

    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(service, "ontologyClient", client);
        ReflectionTestUtils.setField(service, "maxInferredAxioms", 1000);
        ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 60);
        Mockito.when(client.fetchOntologyWithStamp(Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> new OntologyClientService.FetchedOntology(garage(), 7L, null));
    }

    @AfterEach
    void tearDown() {
        OwnerContext.clear();
        blockers.shutdownNow();
    }

    private static OWLOntology garage() throws Exception {
        try (var in = Owl2RlDerivationTest.class.getResourceAsStream("/garage_rl.ttl")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(new StringDocumentSource(text));
        }
    }

    private static boolean has(SwrlEngineService.Owl2RlResult result, String type, String... fragments) {
        return result.axioms().stream().anyMatch(a -> a.getAxiomType().equals(type)
                && java.util.Arrays.stream(fragments).allMatch(f -> a.getDescription().contains(NS + f)));
    }

    private static boolean hasDescription(SwrlEngineService.Owl2RlResult result, String start) {
        return result.axioms().stream().anyMatch(a -> a.getDescription().startsWith(start));
    }

    @Test
    void subclassTransitivityIsDerived() {
        SwrlEngineService.Owl2RlResult result = service.deriveOwl2Rl(PROJECT, null);

        assertTrue(result.success(), String.valueOf(result.errorMessage()));
        assertTrue(has(result, "SubClassOf", "Hatchback", "Vehicle"), result.axioms().toString());
        assertFalse(has(result, "SubClassOf", "Hatchback", "Car") && hasDescription(result, "SubClassOf(<" + NS + "Hatchback> <" + NS + "Car>)"),
                "an asserted subclass axiom is not new");
    }

    @Test
    void domainAndRangeTypeTheIndividuals() {
        SwrlEngineService.Owl2RlResult result = service.deriveOwl2Rl(PROJECT, null);

        assertTrue(hasDescription(result, "ClassAssertion(<" + NS + "Driver> <" + NS + "ann>)"), "domain of drives gives ann the type Driver");
        assertTrue(hasDescription(result, "ClassAssertion(<" + NS + "Vehicle> <" + NS + "bolt>)"), "range of drives gives bolt the type Vehicle");
        assertTrue(hasDescription(result, "ClassAssertion(<" + NS + "Car> <" + NS + "bolt>)"), "bolt is a Hatchback which is a Car");
    }

    @Test
    void inverseTransitiveAndSymmetricAssertionsAreDerived() {
        SwrlEngineService.Owl2RlResult result = service.deriveOwl2Rl(PROJECT, null);

        assertTrue(hasDescription(result, "ObjectPropertyAssertion(<" + NS + "drivenBy> <" + NS + "bolt> <" + NS + "ann>)"),
                "inverse of drives");
        assertTrue(hasDescription(result, "ObjectPropertyAssertion(<" + NS + "partOf> <" + NS + "wheel> <" + NS + "bolt>)"),
                "partOf is transitive");
        assertTrue(hasDescription(result, "ObjectPropertyAssertion(<" + NS + "servesAs> <" + NS + "taxi> <" + NS + "bolt>)"),
                "servesAs is symmetric");
    }

    @Test
    void propertyChainsAreNotDerivedBecauseTheEngineHasNoRuleForThem() {
        SwrlEngineService.Owl2RlResult result = service.deriveOwl2Rl(PROJECT, null);

        assertFalse(hasDescription(result, "ObjectPropertyAssertion(<" + NS + "registeredIn> <" + NS + "bolt> <" + NS + "oslo>)"));
        assertTrue(result.unsupportedRules().contains("prp-spo2"), result.unsupportedRules().toString());
    }

    @Test
    void factsAlreadyInTheOntologyAreNotReturnedAndNothingIsTrivial() {
        SwrlEngineService.Owl2RlResult result = service.deriveOwl2Rl(PROJECT, null);

        assertFalse(hasDescription(result, "ObjectPropertyAssertion(<" + NS + "drives> <" + NS + "ann> <" + NS + "bolt>)"));
        assertFalse(hasDescription(result, "ClassAssertion(<" + NS + "Hatchback> <" + NS + "bolt>)"));
        assertTrue(result.axioms().stream().noneMatch(a -> a.getDescription().contains("owl:Thing")
                || a.getAxiomType().equals("Declaration") || a.getDescription().startsWith("EquivalentClasses(<" + NS + "Car>)")));
        assertTrue(result.omittedTrivialCount() > 0);
        assertFalse(result.truncated());
        assertEquals(result.derivedCount(), result.axioms().size());
        assertEquals(result.axioms().size(), result.typeCounts().values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(result.axioms().stream().allMatch(a -> a.getCategory() != null));
    }

    @Test
    void userSwrlRulesAreNotRunOnlyTheStandardReasoningIs() throws Exception {
        OWLOntology withRule = garage();
        OWLDataFactory factory = OWLManager.getOWLDataFactory();
        org.semanticweb.owlapi.model.SWRLVariable x = factory.getSWRLVariable(IRI.create(NS + "x"));
        withRule.getOWLOntologyManager().addAxiom(withRule, factory.getSWRLRule(
                java.util.Set.of(factory.getSWRLClassAtom(factory.getOWLClass(IRI.create(NS + "City")), x)),
                java.util.Set.of(factory.getSWRLClassAtom(factory.getOWLClass(IRI.create(NS + "Driver")), x))));
        Mockito.when(client.fetchOntologyWithStamp(Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> new OntologyClientService.FetchedOntology(withRule, 7L, null));

        SwrlEngineService.Owl2RlResult result = service.deriveOwl2Rl(PROJECT, null);

        assertTrue(result.success(), String.valueOf(result.errorMessage()));
        assertFalse(hasDescription(result, "ClassAssertion(<" + NS + "Driver> <" + NS + "oslo>)"), "the City to Driver rule must not run");
        assertTrue(hasDescription(result, "ClassAssertion(<" + NS + "Driver> <" + NS + "ann>)"));
    }

    @Test
    void theResultIsCappedAndFlaggedTruncatedWithTheTrueTotalKept() {
        int all = service.deriveOwl2Rl(PROJECT, null).derivedCount();
        assertTrue(all > 3);
        ReflectionTestUtils.setField(service, "owl2rlMaxAxioms", 3);

        SwrlEngineService.Owl2RlResult result = service.deriveOwl2Rl(PROJECT, null);

        assertTrue(result.truncated());
        assertEquals(3, result.axioms().size());
        assertEquals(all, result.derivedCount());
        assertEquals(3, result.maxAxioms());
        assertEquals(all, result.typeCounts().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals("conclusion", result.axioms().get(0).getCategory());
    }

    @Test
    void aDraftRunFetchesTheCallersDraftAndCarriesTheDraftStamp() throws Exception {
        Mockito.when(client.fetchOntologyWithStamp(PROJECT, "user-9"))
                .thenAnswer(invocation -> new OntologyClientService.FetchedOntology(garage(), 7L, "draft-v3"));

        SwrlEngineService.Owl2RlResult draft = service.deriveOwl2Rl(PROJECT, "user-9");
        SwrlEngineService.Owl2RlResult published = service.deriveOwl2Rl(PROJECT, null);

        assertTrue(draft.success());
        assertEquals("draft-v3", draft.stamp().draftVersion());
        assertEquals(null, draft.stamp().ontologyRevision());
        assertEquals(7L, published.stamp().ontologyRevision());
        assertEquals(null, published.stamp().draftVersion());
        Mockito.verify(client).fetchOntologyWithStamp(PROJECT, "user-9");
        Mockito.verify(client).fetchOntologyWithStamp(PROJECT, null);
    }

    @Test
    void aRefusedCallNeverFetchesTheOntologyAndTheSlotIsReleasedAfterwards() throws Exception {
        ExecutionGate gate = new ExecutionGate(1, 1, 5, 5, 10000);
        ReflectionTestUtils.setField(service, "executionGate", gate);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            String owner = "filler-" + i;
            blockers.submit(() -> gate.call(owner, () -> {
                started.countDown();
                release.await();
                return null;
            }, 60));
            if (i == 0) {
                assertTrue(started.await(3, TimeUnit.SECONDS));
            }
        }
        long deadline = System.currentTimeMillis() + 3000;
        while (gate.stats().queued() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        OwnerContext.set(OWNER);

        SwrlBusyException busy = assertThrows(SwrlBusyException.class, () -> service.deriveOwl2Rl(PROJECT, null));

        assertEquals(ExecutionGate.QUEUE_FULL, busy.getReason());
        Mockito.verifyNoInteractions(client);
        release.countDown();
        deadline = System.currentTimeMillis() + 3000;
        while ((gate.stats().active() != 0 || gate.stats().queued() != 0) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(service.deriveOwl2Rl(PROJECT, null).success());
    }

    @Test
    void aSecondRunOfTheSameOwnerIsRefusedWhileTheFirstIsStillWaiting() throws Exception {
        ExecutionGate gate = new ExecutionGate(1, 5, 1, 1, 10000);
        ReflectionTestUtils.setField(service, "executionGate", gate);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            blockers.submit(() -> gate.call(OWNER, () -> {
                started.countDown();
                release.await();
                return null;
            }, 60));
            if (i == 0) {
                assertTrue(started.await(3, TimeUnit.SECONDS));
            }
        }
        Thread.sleep(300);
        OwnerContext.set(OWNER);

        SwrlBusyException busy = assertThrows(SwrlBusyException.class, () -> service.deriveOwl2Rl(PROJECT, null));

        assertEquals(ExecutionGate.OWNER_LIMIT, busy.getReason());
        release.countDown();
    }

    @Test
    void aRunPastTheTimeLimitFailsWithTimeoutAndReleasesTheSlot() throws Exception {
        ExecutionGate gate = new ExecutionGate(1, 1, 1, 1, 10000);
        ReflectionTestUtils.setField(service, "executionGate", gate);
        ReflectionTestUtils.setField(service, "executionTimeoutSeconds", 1);
        ReflectionTestUtils.setField(service, "fetchAllowanceSeconds", 0);
        Mockito.doAnswer(invocation -> {
            Thread.sleep(5000);
            return null;
        }).when(client).fetchOntologyWithStamp(Mockito.anyString(), Mockito.any());
        long begun = System.currentTimeMillis();

        SwrlEngineService.Owl2RlResult result = service.deriveOwl2Rl(PROJECT, null);

        assertFalse(result.success());
        assertEquals("timeout", result.completion());
        assertEquals("Execution timed out after 1 seconds", result.errorMessage());
        assertTrue(System.currentTimeMillis() - begun < 4000);
        assertTrue(result.axioms().isEmpty());
        long deadline = System.currentTimeMillis() + 3000;
        while (gate.stats().active() != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, gate.stats().active());
    }

    @Test
    void aTooLargeOntologyGivesThePlainMessage() throws Exception {
        Mockito.doThrow(new OntologyTooLargeException(150000)).when(client).fetchOntologyWithStamp(Mockito.anyString(), Mockito.any());

        SwrlEngineService.Owl2RlResult result = service.deriveOwl2Rl(PROJECT, null);

        assertFalse(result.success());
        assertEquals("failed", result.completion());
        assertTrue(result.errorMessage().startsWith("This ontology is too large for the rule engine on this server"), result.errorMessage());
    }

    @Test
    void anOntologyOverTheOwl2RlSizeLimitIsRefusedBeforeTheEngineStarts() {
        ReflectionTestUtils.setField(service, "owl2rlMaxOntologyAxioms", 10);

        SwrlEngineService.Owl2RlResult result = service.deriveOwl2Rl(PROJECT, null);

        assertFalse(result.success());
        assertEquals("failed", result.completion());
        assertTrue(result.errorMessage().contains("the limit is about 10 axioms"), result.errorMessage());
        assertTrue(result.axioms().isEmpty());
    }

    @Test
    void anEditorFailureIsReportedAsAFailedRun() throws Exception {
        Mockito.doThrow(new org.semanticweb.owlapi.model.OWLOntologyCreationException("editor is down"))
                .when(client).fetchOntologyWithStamp(Mockito.anyString(), Mockito.any());

        SwrlEngineService.Owl2RlResult result = service.deriveOwl2Rl(PROJECT, null);

        assertFalse(result.success());
        assertNotNull(result.errorMessage());
    }

    @Test
    void theAxiomRowsCarryTheSameFieldsAsExecuteAll() {
        SwrlEngineService.Owl2RlResult result = service.deriveOwl2Rl(PROJECT, null);

        List<InferredAxiom> assertions = result.axioms().stream().filter(a -> a.getAxiomType().equals("ObjectPropertyAssertion")).toList();
        assertFalse(assertions.isEmpty());
        InferredAxiom inverse = assertions.stream().filter(a -> NS.concat("drivenBy").equals(a.getPredicateIri())).findFirst().orElseThrow();
        assertEquals(NS + "bolt", inverse.getSubjectIri());
        assertEquals(NS + "ann", inverse.getObjectIri());
        assertEquals("conclusion", inverse.getCategory());
    }
}
