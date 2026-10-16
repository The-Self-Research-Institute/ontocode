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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        controller = new ProjectLoadController(storageManager, mock(ProjectMetadataService.class),
                mock(ProjectImportService.class), mock(GridFSFileService.class), mock(ProjectShareService.class),
                mock(DraftTrackingService.class), mock(DraftCopyService.class), mock(OntologyHistoryService.class),
                mock(DraftChangeRepository.class), mock(SimpMessagingTemplate.class), datasetService,
                mock(ProjectRepository.class), mock(OntologyPreparseService.class), mock(ImportWorkerDispatcher.class),
                mock(MongoTemplate.class), mock(OntologyExportJobService.class), mock(CodeViewReimportPipeline.class),
                new ProjectWriteLockRegistry());
        ReflectionTestUtils.setField(controller, "owlEditorReasonerService", reasonerService);
        ReflectionTestUtils.setField(controller, "quickUnsatExecutor", new DirectExecutorService());

        exportFile = Files.createTempFile("quick-unsat-", ".owl");
        Files.writeString(exportFile, ONTOLOGY);
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(7L);
        when(storageManager.exportOntology("proj-1", "rdfxml")).thenReturn(exportFile);
        when(datasetService.getDatasetSizeOrUnknown("proj-1")).thenReturn(10L);
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
        when(reasonerService.getUnsatisfiableClassesQuick(any(), eq(ReasonerType.HERMIT), any()))
                .thenReturn(new ReasonerService.QuickUnsatResult(List.of("http://example.org/quick#A"), false));

        Map<String, Object> body = check();

        assertEquals(true, body.get("success"));
        assertEquals(List.of("http://example.org/quick#A"), body.get("unsatisfiableClasses"));
        assertTrue(inFlight().isEmpty());
    }

    @Test
    void failedRunIsNotReusedByTheNextRequest() {
        when(reasonerService.getUnsatisfiableClassesQuick(any(), eq(ReasonerType.HERMIT), any()))
                .thenThrow(new IllegalStateException("reasoner crashed"))
                .thenReturn(new ReasonerService.QuickUnsatResult(List.of(), false));

        assertEquals(false, check().get("success"));
        assertTrue(inFlight().isEmpty());
        assertEquals(true, check().get("success"));
        verify(reasonerService, times(2)).getUnsatisfiableClassesQuick(any(), eq(ReasonerType.HERMIT), any());
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
