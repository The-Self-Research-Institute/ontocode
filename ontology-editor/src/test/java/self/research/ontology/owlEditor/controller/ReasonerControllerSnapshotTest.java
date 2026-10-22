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
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.model.DLQueryJob;
import self.research.ontology.owlEditor.service.EditorJobResultRetention;
import self.research.ontology.owlEditor.service.EditorReasonerCacheService;
import self.research.ontology.owlEditor.service.MainGraphRevisionService;
import self.research.ontology.owlEditor.service.ReasonerService;
import self.research.ontology.owlEditor.service.ReasonerType;
import self.research.ontology.owlEditor.service.ReasonerWorkerClient;
import self.research.ontology.owlEditor.service.ReasoningJobRelayService;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.StorageManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReasonerControllerSnapshotTest {

    private static final String NS = "http://example.org/snap#";

    private ReasonerController controller;
    private ReasonerWorkerClient workerClient;
    private ReasonerService reasonerService;
    private EditorReasonerCacheService cache;
    private SparqlDatasetService datasetService;
    private StorageManager storageManager;
    private SimpMessagingTemplate messaging;
    private ReasoningJobRelayService relay;
    private final Map<String, DLQueryJob> retained = new HashMap<>();
    private final List<OWLReasoner> classReasonersHandedOut = new ArrayList<>();

    @BeforeEach
    void setUp() {
        controller = new ReasonerController();
        workerClient = mock(ReasonerWorkerClient.class);
        reasonerService = spy(new ReasonerService());
        ReflectionTestUtils.setField(reasonerService, "reasonerOperationTimeoutMs", 30_000L);
        doAnswer(inv -> {
            OWLReasoner reasoner = (OWLReasoner) inv.callRealMethod();
            classReasonersHandedOut.add(reasoner);
            return reasoner;
        }).when(reasonerService).getClassHierarchyReasoner(any(), any());
        cache = new EditorReasonerCacheService(reasonerService);
        ReflectionTestUtils.setField(cache, "maxOntologies", 2);
        ReflectionTestUtils.setField(cache, "maxHierarchies", 8);
        ReflectionTestUtils.setField(cache, "maxSnapshots", 4);
        ReflectionTestUtils.setField(cache, "heapComfortUsedRatio", 1.1);
        ReflectionTestUtils.setField(cache, "heapPressureUsedRatio", 1.1);
        datasetService = mock(SparqlDatasetService.class);
        storageManager = mock(StorageManager.class);

        EditorJobResultRetention retention = mock(EditorJobResultRetention.class);
        when(retention.get(anyString())).thenAnswer(inv -> Optional.ofNullable(retained.get(inv.getArgument(0))));
        doAnswer(inv -> {
            DLQueryJob job = inv.getArgument(0);
            retained.put(job.getJobId(), job);
            return null;
        }).when(retention).retain(any());
        messaging = mock(SimpMessagingTemplate.class);
        relay = new ReasoningJobRelayService(messaging, retention);

        ReflectionTestUtils.setField(controller, "reasonerWorkerClient", workerClient);
        ReflectionTestUtils.setField(controller, "reasonerWorkerEnabled", true);
        ReflectionTestUtils.setField(controller, "reasoningJobRelayService", relay);
        ReflectionTestUtils.setField(controller, "reasonerService", reasonerService);
        ReflectionTestUtils.setField(controller, "editorReasonerCache", cache);
        ReflectionTestUtils.setField(controller, "datasetService", datasetService);
        ReflectionTestUtils.setField(controller, "storageManager", storageManager);
        ReflectionTestUtils.setField(controller, "maxReasonerTriples", 5_000_000L);
    }

    @AfterEach
    void tearDown() {
        reasonerService.clearCache();
    }

    private static OWLOntology smallOntology() throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create("http://example.org/snap"));
        OWLClass animal = df.getOWLClass(IRI.create(NS + "Animal"));
        OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
        OWLClass robotDog = df.getOWLClass(IRI.create(NS + "RobotDog"));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(dog, animal));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(robotDog, dog));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(robotDog, df.getOWLObjectComplementOf(animal)));
        return ontology;
    }

    private Map<String, Object> snapshot(String reasonerType, String userId, boolean draft) {
        ResponseEntity<Map<String, Object>> response = controller.getReasonerSnapshot(
                "proj-1", reasonerType, userId, draft, new MockHttpServletRequest());
        assertEquals(200, response.getStatusCode().value());
        return response.getBody();
    }

    @Test
    void completedSnapshotFromTheWorkerIsReturnedInline() {
        Map<String, Object> submitted = new HashMap<>();
        submitted.put("success", true);
        submitted.put("async", true);
        submitted.put("jobId", "job-1");
        submitted.put("jobType", "REASONER_SNAPSHOT");
        submitted.put("status", "COMPLETED");
        submitted.put("consistent", true);
        submitted.put("classTree", List.of(Map.of("id", "owl:Thing")));
        submitted.put("reasonerType", "HermiT");
        submitted.put("cached", true);
        when(workerClient.submitJob(eq("REASONER_SNAPSHOT"), eq("proj-1"), isNull(), isNull(), eq("HERMIT"), isNull()))
                .thenReturn(submitted);

        Map<String, Object> body = snapshot("hermit", null, false);

        assertEquals(true, body.get("success"));
        assertEquals(false, body.get("async"));
        assertEquals("proj-1", body.get("projectId"));
        assertEquals(true, body.get("consistent"));
        assertEquals("HermiT", body.get("reasonerType"));
        assertFalse(body.containsKey("jobId"));
        verifyNoInteractions(reasonerService);
    }

    @Test
    void completedSnapshotNestedUnderResultIsAlsoReturnedInline() {
        when(workerClient.submitJob(anyString(), anyString(), isNull(), isNull(), anyString(), isNull()))
                .thenReturn(Map.of("success", true, "jobId", "job-1", "status", "COMPLETED",
                        "result", Map.of("success", true, "consistent", false, "reasonerType", "Openllet")));

        Map<String, Object> body = snapshot("OPENLLET", null, false);

        assertEquals(false, body.get("async"));
        assertEquals(false, body.get("consistent"));
        assertEquals("Openllet", body.get("reasonerType"));
    }

    @Test
    void queuedSnapshotIsAsyncAndRelayedForWebSocketPushes() {
        when(workerClient.submitJob(anyString(), anyString(), isNull(), isNull(), anyString(), isNull()))
                .thenReturn(Map.of("success", true, "jobId", "job-2", "status", "QUEUED", "queuePosition", 2));

        Map<String, Object> body = snapshot("HERMIT", null, false);

        assertEquals(true, body.get("async"));
        assertEquals("job-2", body.get("jobId"));
        assertEquals(2, body.get("queuePosition"));
        DLQueryJob relayed = relay.getJob("job-2");
        assertNotNull(relayed);
        assertEquals("proj-1", relayed.getProjectId());
        verify(messaging).convertAndSend(eq("/topic/reasoning/job-2"), any(Object.class));
        verifyNoInteractions(reasonerService);
    }

    @Test
    void fullWorkerQueueAnswersTooManyRequests() {
        when(workerClient.submitJob(anyString(), anyString(), isNull(), isNull(), anyString(), isNull()))
                .thenReturn(Map.of("success", false, "rateLimited", true, "error", "Queue is full"));

        ResponseEntity<Map<String, Object>> response = controller.getReasonerSnapshot(
                "proj-1", "HERMIT", null, false, new MockHttpServletRequest());

        assertEquals(429, response.getStatusCode().value());
        assertEquals("Queue is full", response.getBody().get("error"));
        verifyNoInteractions(reasonerService);
    }

    @Test
    void pollingACompletedSnapshotJobNestsTheSnapshotUnderResult() {
        Map<String, Object> job = new HashMap<>();
        job.put("jobId", "job-3");
        job.put("jobType", "REASONER_SNAPSHOT");
        job.put("status", "COMPLETED");
        job.put("success", true);
        job.put("consistent", true);
        job.put("stats", Map.of("individuals", 3));
        when(workerClient.getJob("job-3")).thenReturn(job);

        Map<String, Object> body = controller.getReasonerJob("proj-1", "job-3").getBody();

        assertEquals("COMPLETED", body.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        assertEquals(true, result.get("consistent"));
        assertEquals(Map.of("individuals", 3), result.get("stats"));
        assertFalse(body.containsKey("consistent"));
    }

    @Test
    void unreachableWorkerFallsBackToTheEditor() throws Exception {
        when(workerClient.submitJob(anyString(), anyString(), isNull(), isNull(), anyString(), isNull()))
                .thenReturn(Map.of("success", false, "error", "The reasoning service is temporarily unavailable."));
        cache.putOntology("proj-1", smallOntology());

        Map<String, Object> body = snapshot("HERMIT", null, false);

        assertEquals(true, body.get("success"));
        assertEquals(false, body.get("async"));
        assertEquals("HermiT", body.get("reasonerType"));
        assertEquals(-1L, body.get("ontologyRevision"));
    }

    @Test
    void draftSnapshotIsBuiltInTheEditorWithTheRequestedHermit() throws Exception {
        OWLOntology draftOntology = smallOntology();
        when(storageManager.getDraftGraphVersion("proj-1", "user-1")).thenReturn(4L);
        when(datasetService.getDraftWriteStamp("proj-1", "user-1")).thenReturn(9L);
        when(datasetService.loadDraftAsOntology("proj-1", "user-1", 4L)).thenReturn(draftOntology);

        Map<String, Object> body = snapshot("HERMIT", "user-1", true);

        verify(workerClient, never()).submitJob(anyString(), anyString(), any(), any(), any(), any());
        assertEquals(true, body.get("success"));
        assertEquals(false, body.get("async"));
        assertEquals(false, body.get("cached"));
        assertEquals(true, body.get("draft"));
        assertEquals("HermiT", body.get("reasonerType"));
        assertEquals("HermiT", body.get("propertyReasonerType"));
        assertEquals("HERMIT", body.get("requestedReasoner"));
        assertEquals(-1L, body.get("ontologyRevision"));
        assertEquals(true, body.get("consistent"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unsatisfiable = (List<Map<String, Object>>) body.get("unsatisfiableClasses");
        assertEquals(NS + "RobotDog", unsatisfiable.get(0).get("iri"));
        assertTrue(body.containsKey("classChildren"));
        verify(reasonerService, never()).getClassHierarchyReasoner(any(), eq(ReasonerType.OPENLLET));
    }

    @Test
    void hermitThatCannotHandleSwrlBuiltInsFallsBackToOpenllet() throws Exception {
        OWLOntology draftOntology = smallOntology();
        OWLOntologyManager manager = draftOntology.getOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLClass dog = df.getOWLClass(IRI.create(NS + "Dog"));
        OWLClass oldDog = df.getOWLClass(IRI.create(NS + "OldDog"));
        org.semanticweb.owlapi.model.OWLDataProperty age = df.getOWLDataProperty(IRI.create(NS + "age"));
        org.semanticweb.owlapi.model.SWRLVariable x = df.getSWRLVariable(IRI.create(NS + "x"));
        org.semanticweb.owlapi.model.SWRLVariable a = df.getSWRLVariable(IRI.create(NS + "a"));
        manager.addAxiom(draftOntology, df.getSWRLRule(
                java.util.Set.of(
                        df.getSWRLClassAtom(dog, x),
                        df.getSWRLDataPropertyAtom(age, x, a),
                        df.getSWRLBuiltInAtom(IRI.create("http://www.w3.org/2003/11/swrlb#greaterThan"),
                                List.of(a, df.getSWRLLiteralArgument(df.getOWLLiteral(10))))),
                java.util.Set.of(df.getSWRLClassAtom(oldDog, x))));
        when(storageManager.getDraftGraphVersion("proj-1", "user-1")).thenReturn(4L);
        when(datasetService.getDraftWriteStamp("proj-1", "user-1")).thenReturn(9L);
        when(datasetService.loadDraftAsOntology("proj-1", "user-1", 4L)).thenReturn(draftOntology);

        Map<String, Object> body = snapshot("HERMIT", "user-1", true);

        assertEquals(true, body.get("success"));
        assertEquals("Openllet", body.get("reasonerType"));
        assertEquals("HERMIT", body.get("requestedReasoner"));
        assertTrue(String.valueOf(body.get("downgradedWarning")).startsWith("HermiT could not be started"));
        assertEquals(true, body.get("consistent"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unsatisfiable = (List<Map<String, Object>>) body.get("unsatisfiableClasses");
        assertEquals(NS + "RobotDog", unsatisfiable.get(0).get("iri"));
    }

    @Test
    void repeatOnTheSameDraftVersionIsServedFromCacheAndADraftWriteClearsIt() throws Exception {
        OWLOntology draftOntology = smallOntology();
        when(storageManager.getDraftGraphVersion("proj-1", "user-1")).thenReturn(4L);
        when(datasetService.getDraftWriteStamp("proj-1", "user-1")).thenReturn(9L);
        when(datasetService.loadDraftAsOntology("proj-1", "user-1", 4L)).thenReturn(draftOntology);

        snapshot("HERMIT", "user-1", true);
        Map<String, Object> repeat = snapshot("HERMIT", "user-1", true);

        assertEquals(true, repeat.get("cached"));
        verify(reasonerService, times(1)).getClassHierarchyReasoner(any(), any());

        cache.invalidateDraft("proj-1", "user-1");
        Map<String, Object> afterWrite = snapshot("HERMIT", "user-1", true);

        assertEquals(false, afterWrite.get("cached"));
        verify(reasonerService, times(2)).getClassHierarchyReasoner(any(), any());
        assertEquals(2, classReasonersHandedOut.size());
        assertSame(classReasonersHandedOut.get(0), classReasonersHandedOut.get(1));
    }

    @Test
    void publicEditDoesNotDropAnotherUsersDraftSnapshot() throws Exception {
        OWLOntology draftOntology = smallOntology();
        when(storageManager.getDraftGraphVersion("proj-1", "user-1")).thenReturn(4L);
        when(datasetService.loadDraftAsOntology("proj-1", "user-1", 4L)).thenReturn(draftOntology);
        snapshot("HERMIT", "user-1", true);

        cache.invalidateOntology("proj-1");

        assertEquals(true, snapshot("HERMIT", "user-1", true).get("cached"));
    }

    @Test
    void publishedSnapshotWithoutAWorkerIsCachedPerRevision() throws Exception {
        ReflectionTestUtils.setField(controller, "reasonerWorkerEnabled", false);
        MainGraphRevisionService revisions = mock(MainGraphRevisionService.class);
        when(revisions.getRevision("proj-1")).thenReturn(12L);
        ReflectionTestUtils.setField(controller, "mainGraphRevisionService", revisions);
        cache.putOntology("proj-1", smallOntology());

        Map<String, Object> first = snapshot("HERMIT", null, false);
        Map<String, Object> second = snapshot("HERMIT", null, false);
        when(revisions.getRevision("proj-1")).thenReturn(13L);
        Map<String, Object> afterEdit = snapshot("HERMIT", null, false);

        assertEquals(12L, first.get("ontologyRevision"));
        assertEquals(false, first.get("cached"));
        assertEquals(true, second.get("cached"));
        assertEquals(false, afterEdit.get("cached"));
        assertEquals(13L, afterEdit.get("ontologyRevision"));
        verifyNoInteractions(workerClient);
    }

    @Test
    void unknownReasonerTypeIsAFriendlyError() {
        Map<String, Object> body = snapshot("NOPE", null, false);

        assertEquals(false, body.get("success"));
        assertNotNull(body.get("error"));
        assertNotNull(body.get("technicalDetail"));
    }
}
