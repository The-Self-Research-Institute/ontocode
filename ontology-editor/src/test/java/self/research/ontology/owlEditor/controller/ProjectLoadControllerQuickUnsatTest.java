package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.repository.DraftChangeRepository;
import self.research.ontology.owlEditor.repository.ProjectRepository;
import self.research.ontology.owlEditor.service.CodeViewReimportPipeline;
import self.research.ontology.owlEditor.service.DraftCopyService;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.EditorReasonerCacheService;
import self.research.ontology.owlEditor.service.GridFSFileService;
import self.research.ontology.owlEditor.service.ImportWorkerDispatcher;
import self.research.ontology.owlEditor.service.OntologyExportJobService;
import self.research.ontology.owlEditor.service.OntologyHistoryService;
import self.research.ontology.owlEditor.service.OntologyPreparseService;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.ProjectShareService;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.ReasonerService;
import self.research.ontology.owlEditor.service.ReasonerType;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.StorageManager;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.NTriplesDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectLoadControllerQuickUnsatTest {

    private static final String ONTOLOGY = """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                     xmlns:owl="http://www.w3.org/2002/07/owl#">
                <owl:Ontology rdf:about="http://example.org/quick"/>
                <owl:Class rdf:about="http://example.org/quick#A"/>
            </rdf:RDF>
            """;

    private StorageManager storageManager;
    private SparqlDatasetService datasetService;
    private ReasonerService reasonerService;
    private ProjectLoadController controller;
    private Path exportFile;

    private static final class DirectExecutorService extends AbstractExecutorService {
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public void execute(Runnable command) { command.run(); }
    }

    @BeforeEach
    void setUp() throws Exception {
        storageManager = mock(StorageManager.class);
        datasetService = mock(SparqlDatasetService.class);
        reasonerService = mock(ReasonerService.class);
        controller = newController();
        ReflectionTestUtils.setField(controller, "owlEditorReasonerService", reasonerService);
        ReflectionTestUtils.setField(controller, "quickUnsatExecutor", new DirectExecutorService());

        exportFile = Files.createTempFile("quick-unsat-", ".owl");
        Files.writeString(exportFile, ONTOLOGY);
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(7L);
        when(storageManager.exportOntology("proj-1", "rdfxml")).thenReturn(exportFile);
        when(datasetService.getDatasetSizeOrUnknown("proj-1")).thenReturn(10L);
        when(reasonerService.withoutSwrlRules(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ProjectLoadController newController() {
        return new ProjectLoadController(storageManager, mock(ProjectMetadataService.class),
                mock(ProjectImportService.class), mock(GridFSFileService.class), mock(ProjectShareService.class),
                mock(DraftTrackingService.class), mock(DraftCopyService.class), mock(OntologyHistoryService.class),
                mock(DraftChangeRepository.class), mock(SimpMessagingTemplate.class), datasetService,
                mock(ProjectRepository.class), mock(OntologyPreparseService.class), mock(ImportWorkerDispatcher.class),
                mock(MongoTemplate.class), mock(OntologyExportJobService.class), mock(CodeViewReimportPipeline.class),
                new ProjectWriteLockRegistry());
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(exportFile);
    }

    private Map<String, Object> check() {
        ResponseEntity<Map<String, Object>> response =
                controller.getUnsatisfiableClassesQuick("proj-1", null, false, new MockHttpServletRequest());
        return response.getBody();
    }

    private Map<?, ?> inFlight() {
        return (Map<?, ?>) ReflectionTestUtils.getField(controller, "quickUnsatInFlight");
    }

    @Test
    void runThatFinishesImmediatelyIsReturnedAndCleanedUp() {
        when(reasonerService.getUnsatisfiableClassesQuickWithoutSwrl(any(), eq(ReasonerType.HERMIT), any()))
                .thenReturn(new ReasonerService.QuickUnsatResult(List.of("http://example.org/quick#A"), false));

        Map<String, Object> body = check();

        assertEquals(true, body.get("success"));
        assertEquals(List.of("http://example.org/quick#A"), body.get("unsatisfiableClasses"));
        assertTrue(inFlight().isEmpty());
    }

    @Test
    void failedRunIsNotReusedByTheNextRequest() throws Exception {
        when(reasonerService.getUnsatisfiableClassesQuickWithoutSwrl(any(), eq(ReasonerType.HERMIT), any()))
                .thenThrow(new IllegalStateException("reasoner crashed"))
                .thenReturn(new ReasonerService.QuickUnsatResult(List.of(), false));

        assertEquals(false, check().get("success"));
        assertTrue(inFlight().isEmpty());
        assertEquals(true, check().get("success"));
        verify(reasonerService, times(2)).getUnsatisfiableClassesQuickWithoutSwrl(any(), eq(ReasonerType.HERMIT), any());
        verify(reasonerService, times(1)).withoutSwrlRules(any());
        verify(storageManager, times(1)).exportOntology("proj-1", "rdfxml");
    }

    @Test
    void ontologyAlreadyLoadedForReasoningAtTheCurrentVersionIsReusedInsteadOfExporting() throws Exception {
        EditorReasonerCacheService cache = mock(EditorReasonerCacheService.class);
        OWLOntology loaded = OWLManager.createOWLOntologyManager().createOntology();
        when(cache.getOntologyAtVersion("proj-1", 7L)).thenReturn(Optional.of(loaded));
        ReflectionTestUtils.setField(controller, "editorReasonerCache", cache);
        when(reasonerService.getUnsatisfiableClassesQuickWithoutSwrl(any(), eq(ReasonerType.HERMIT), any()))
                .thenReturn(new ReasonerService.QuickUnsatResult(List.of(), false));

        assertEquals(true, check().get("success"));

        verify(reasonerService).withoutSwrlRules(loaded);
        verify(storageManager, never()).exportOntology(any(), any());
    }

    @Test
    void ontologyLoadedAtAnOlderVersionIsNotReused() throws Exception {
        EditorReasonerCacheService cache = new EditorReasonerCacheService(reasonerService);
        cache.putOntology("proj-1", OWLManager.createOWLOntologyManager().createOntology(), 6L);
        ReflectionTestUtils.setField(controller, "editorReasonerCache", cache);
        when(reasonerService.getUnsatisfiableClassesQuickWithoutSwrl(any(), eq(ReasonerType.HERMIT), any()))
                .thenReturn(new ReasonerService.QuickUnsatResult(List.of(), false));

        assertEquals(true, check().get("success"));

        verify(storageManager, times(1)).exportOntology("proj-1", "rdfxml");
    }

    @Test
    void reusedOntologyGivesTheSameUnsatisfiableClassesAsTheExportOnThePizzaOntology() throws Exception {
        Path pizzaFile = Path.of("..", "test-data", "PizzaTutorialWithDataV2 (1).owl").normalize();
        OWLOntologyManager pizzaManager = OWLManager.createOWLOntologyManager();
        OWLOntology pizza;
        try (InputStream in = Files.newInputStream(pizzaFile)) {
            pizza = pizzaManager.loadOntologyFromOntologyDocument(in);
        }
        OWLDataFactory df = pizzaManager.getOWLDataFactory();
        OWLClass pizzaClass = pizza.getClassesInSignature().stream()
                .filter(c -> c.getIRI().toString().endsWith("#Pizza")).findFirst().orElseThrow();
        OWLClass impossible = df.getOWLClass(IRI.create(pizzaClass.getIRI() + "ThatIsNotAPizza"));
        pizzaManager.addAxiom(pizza, df.getOWLSubClassOfAxiom(impossible, pizzaClass));
        pizzaManager.addAxiom(pizza, df.getOWLSubClassOfAxiom(impossible, df.getOWLObjectComplementOf(pizzaClass)));
        Path exported = Files.createTempFile("quick-unsat-pizza-", ".owl");
        try (OutputStream out = Files.newOutputStream(exported)) {
            pizzaManager.saveOntology(pizza, new RDFXMLDocumentFormat(), out);
        }
        ByteArrayOutputStream nTriples = new ByteArrayOutputStream();
        pizzaManager.saveOntology(pizza, new NTriplesDocumentFormat(), nTriples);

        ReasonerService realReasoner = new ReasonerService();
        ReflectionTestUtils.setField(realReasoner, "reasonerOperationTimeoutMs", 60_000L);
        try {
            when(storageManager.exportOntology("proj-1", "rdfxml")).thenReturn(exported);
            ReflectionTestUtils.setField(controller, "owlEditorReasonerService", realReasoner);
            Map<String, Object> fromExport = check();

            ProjectLoadController reusing = newController();
            ReflectionTestUtils.setField(reusing, "owlEditorReasonerService", realReasoner);
            ReflectionTestUtils.setField(reusing, "quickUnsatExecutor", new DirectExecutorService());
            EditorReasonerCacheService cache = new EditorReasonerCacheService(realReasoner);
            OWLOntology loadedForReasoning = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(
                    new ByteArrayInputStream(nTriples.toByteArray()));
            cache.putOntology("proj-1", loadedForReasoning, 7L);
            ReflectionTestUtils.setField(reusing, "editorReasonerCache", cache);
            clearInvocations(storageManager);

            Map<String, Object> fromLoaded = reusing.getUnsatisfiableClassesQuick("proj-1", null, false,
                    new MockHttpServletRequest()).getBody();

            verify(storageManager, never()).exportOntology(any(), any());
            assertEquals(true, fromExport.get("success"));
            assertEquals(true, fromLoaded.get("success"));
            assertEquals(false, fromExport.get("inconsistent"));
            assertEquals(fromExport.get("inconsistent"), fromLoaded.get("inconsistent"));
            assertEquals(List.of(impossible.getIRI().toString()), fromExport.get("unsatisfiableClasses"));
            assertEquals(new HashSet<>((List<?>) fromExport.get("unsatisfiableClasses")),
                    new HashSet<>((List<?>) fromLoaded.get("unsatisfiableClasses")));
        } finally {
            Files.deleteIfExists(exported);
        }
    }

    @Test
    void unknownSizeIsSkippedWithAReason() {
        when(datasetService.getDatasetSizeOrUnknown("proj-1")).thenReturn(-1L);

        Map<String, Object> body = check();

        assertEquals(true, body.get("success"));
        assertEquals(true, body.get("skipped"));
        assertEquals("SIZE_UNKNOWN", body.get("reason"));
    }
}
