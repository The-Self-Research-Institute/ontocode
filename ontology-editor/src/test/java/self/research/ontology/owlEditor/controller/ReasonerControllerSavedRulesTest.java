package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.common.preview.PreviewStamp;
import self.research.ontology.owlEditor.service.EditorReasonerCacheService;
import self.research.ontology.owlEditor.service.OntologyVersionStamps;
import self.research.ontology.owlEditor.service.ReasonerService;
import self.research.ontology.owlEditor.service.SavedRulesFixtures;
import self.research.ontology.owlEditor.service.SavedRulesService;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.StorageManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReasonerControllerSavedRulesTest {

    private static final String NS = SavedRulesFixtures.NS;

    private ReasonerController controller;
    private ReasonerService reasonerService;
    private EditorReasonerCacheService cache;
    private SparqlDatasetService datasetService;
    private StorageManager storageManager;
    private OntologyVersionStamps stamps;

    @BeforeEach
    void setUp() throws Exception {
        controller = new ReasonerController();
        reasonerService = new ReasonerService();
        ReflectionTestUtils.setField(reasonerService, "reasonerOperationTimeoutMs", 30_000L);
        cache = new EditorReasonerCacheService(reasonerService);
        ReflectionTestUtils.setField(cache, "maxOntologies", 2);
        ReflectionTestUtils.setField(cache, "maxHierarchies", 8);
        ReflectionTestUtils.setField(cache, "maxSnapshots", 4);
        ReflectionTestUtils.setField(cache, "heapComfortUsedRatio", 1.1);
        ReflectionTestUtils.setField(cache, "heapPressureUsedRatio", 1.1);
        datasetService = mock(SparqlDatasetService.class);
        storageManager = mock(StorageManager.class);
        stamps = mock(OntologyVersionStamps.class);
        ReflectionTestUtils.setField(controller, "reasonerWorkerEnabled", false);
        ReflectionTestUtils.setField(controller, "reasonerService", reasonerService);
        ReflectionTestUtils.setField(controller, "editorReasonerCache", cache);
        ReflectionTestUtils.setField(controller, "datasetService", datasetService);
        ReflectionTestUtils.setField(controller, "storageManager", storageManager);
        ReflectionTestUtils.setField(controller, "ontologyVersionStamps", stamps);
        ReflectionTestUtils.setField(controller, "maxReasonerTriples", 5_000_000L);
        controller.useRuleStage((ontology, rules) -> Optional.of(java.util.Set.of()));
        when(storageManager.getDraftGraphVersion("proj-1", "user-1")).thenReturn(4L);
        when(datasetService.getDraftWriteStamp("proj-1", "user-1")).thenReturn(9L);
        when(datasetService.loadDraftAsOntology("proj-1", "user-1", 4L)).thenAnswer(inv -> personOntology());
        when(stamps.current("proj-1", true, "user-1")).thenReturn(new PreviewStamp(null, "4.9", "fp-1"));
    }

    @AfterEach
    void tearDown() {
        reasonerService.clearCache();
    }

    private static OWLOntology personOntology() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/sr"));
        OWLClass person = df.getOWLClass(IRI.create(NS + "Person"));
        OWLClass adult = df.getOWLClass(IRI.create(NS + "Adult"));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(adult, person));
        manager.addAxiom(ontology, df.getOWLClassAssertionAxiom(person, df.getOWLNamedIndividual(IRI.create(NS + "john"))));
        return ontology;
    }

    private Map<String, Object> snapshot() {
        ResponseEntity<Map<String, Object>> response = controller.getReasonerSnapshot(
                "proj-1", "HERMIT", "user-1", true, new MockHttpServletRequest());
        assertEquals(200, response.getStatusCode().value());
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute() {
        ResponseEntity<Map<String, Object>> response = controller.executeRules(
                "proj-1", "user-1", true, new MockHttpServletRequest());
        assertEquals(200, response.getStatusCode().value());
        return response.getBody();
    }

    @Test
    void aSavedRuleTakesPartInTheInferredTreeReasoning() throws Exception {
        String conflictingRule = "Prefix(:=<" + NS + ">)\n"
                + "Ontology(\n"
                + "Declaration(Class(:Person))\n"
                + "Declaration(Class(:Adult))\n"
                + "DLSafeRule(Body(ClassAtom(:Person Variable(<urn:swrl:var#x>))) "
                + "Head(ClassAtom(:Adult Variable(<urn:swrl:var#x>))))\n"
                + ")\n";
        when(datasetService.loadDraftAsOntology("proj-1", "user-1", 4L)).thenAnswer(inv -> {
            OWLOntology ontology = personOntology();
            OWLOntologyManager manager = ontology.getOWLOntologyManager();
            OWLDataFactory df = manager.getOWLDataFactory();
            manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(NS + "Adult")),
                    df.getOWLObjectComplementOf(df.getOWLClass(IRI.create(NS + "Person")))));
            return ontology;
        });
        ReflectionTestUtils.setField(controller, "savedRulesService", new SavedRulesService(
                (url, token) -> SavedRulesFixtures.ok(conflictingRule, "fp-2", null), "http://swrl", "t"));

        Map<String, Object> withRule = snapshot();

        assertEquals(false, withRule.get("consistent"));

        ReflectionTestUtils.setField(controller, "savedRulesService", SavedRulesFixtures.withoutRules());
        Map<String, Object> without = snapshot();

        assertEquals(true, without.get("consistent"));
    }

    @Test
    void aSnapshotBuiltWithoutTheSavedRulesIsPartialWithANoteAndIsNotKept() {
        ReflectionTestUtils.setField(controller, "savedRulesService", SavedRulesFixtures.unreachable());

        Map<String, Object> first = snapshot();
        Map<String, Object> second = snapshot();

        assertEquals("partial", first.get("completion"));
        assertTrue(String.valueOf(first.get("downgradedWarning")).contains(SavedRulesService.UNAVAILABLE_NOTE));
        assertEquals(false, second.get("cached"));
    }

    @Test
    void theSnapshotCacheSeparatesEntriesBySavedRules() {
        ReflectionTestUtils.setField(controller, "savedRulesService", SavedRulesFixtures.withoutRules());
        snapshot();
        assertEquals(true, snapshot().get("cached"));

        ReflectionTestUtils.setField(controller, "savedRulesService", SavedRulesFixtures.withRule());

        assertEquals(false, snapshot().get("cached"));
        assertEquals(true, snapshot().get("cached"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeListsConclusionsFirstWithStatusSkippedRulesAndTheStamp() {
        String skipped = "[{\"ruleName\":\"broken\",\"reason\":\"Unknown class\"}]";
        ReflectionTestUtils.setField(controller, "savedRulesService", new SavedRulesService(
                (url, token) -> SavedRulesFixtures.ok(SavedRulesFixtures.rulesText("adult"), "fp-1", skipped), "http://swrl", "t"));

        Map<String, Object> body = execute();

        assertEquals(true, body.get("success"));
        assertEquals("COMPLETED", body.get("status"));
        assertEquals("Completed within the supported profile", body.get("statusLabel"));
        assertEquals("completed", body.get("completion"));
        assertEquals("all", body.get("executionMode"));
        assertEquals(1, body.get("conclusionCount"));
        List<Map<String, Object>> axioms = (List<Map<String, Object>>) body.get("inferredAxioms");
        Map<String, Object> first = axioms.get(0);
        assertEquals("conclusion", first.get("category"));
        assertEquals(NS + "john", first.get("subjectIri"));
        assertEquals("http://www.w3.org/1999/02/22-rdf-syntax-ns#type", first.get("predicateIri"));
        assertEquals(NS + "Adult", first.get("objectIri"));
        assertTrue(((List<String>) body.get("executedRuleNames")).contains("adult"));
        assertEquals(List.of(Map.of("ruleName", "broken", "reason", "Unknown class")), body.get("skippedRules"));
        assertEquals(new PreviewStamp(null, "4.9", "fp-1"), body.get("stamp"));
        assertNotNull(body.get("engineVersions"));
        assertNotNull(body.get("consistencyStatement"));
        assertNotNull(body.get("snapshotId"));
        assertNotNull(body.get("limitations"));
        assertNotNull(body.get("rounds"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeSaysSoWhenTheSavedRulesCouldNotBeLoaded() {
        ReflectionTestUtils.setField(controller, "savedRulesService", SavedRulesFixtures.unreachable());

        Map<String, Object> body = execute();

        assertEquals(true, body.get("success"));
        assertEquals("completed_with_warnings", body.get("completion"));
        assertTrue(((List<String>) body.get("limitations")).contains(SavedRulesService.UNAVAILABLE_NOTE));
        assertEquals(0, body.get("conclusionCount"));
    }
}
