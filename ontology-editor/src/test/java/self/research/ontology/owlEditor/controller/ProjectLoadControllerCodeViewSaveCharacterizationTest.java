package self.research.ontology.owlEditor.controller;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.cache.ProjectOntologyCache;
import self.research.ontology.owlEditor.model.ImportOptions;
import self.research.ontology.owlEditor.repository.DraftChangeRepository;
import self.research.ontology.owlEditor.repository.ProjectRepository;
import self.research.ontology.owlEditor.service.DraftCopyService;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.GridFSFileService;
import self.research.ontology.owlEditor.service.HierarchyIndexService;
import self.research.ontology.owlEditor.service.ImportWorkerDispatcher;
import self.research.ontology.owlEditor.service.OntologyExportJobService;
import self.research.ontology.owlEditor.service.OntologyHistoryService;
import self.research.ontology.owlEditor.service.OntologyMutationService;
import self.research.ontology.owlEditor.service.OntologyPreparseService;
import self.research.ontology.owlEditor.service.OntologyQueryService;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.ProjectShareService;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.StorageManager;
import self.research.ontology.owlEditor.util.OWLFormatConverter;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectLoadControllerCodeViewSaveCharacterizationTest {

    @Mock
    private StorageManager storageManager;
    @Mock
    private ProjectMetadataService metadataService;
    @Mock
    private ProjectImportService importService;
    @Mock
    private GridFSFileService gridFSFileService;
    @Mock
    private ProjectShareService shareService;
    @Mock
    private DraftTrackingService draftTrackingService;
    @Mock
    private DraftCopyService draftCopyService;
    @Mock
    private OntologyHistoryService historyService;
    @Mock
    private DraftChangeRepository draftChangeRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private SparqlDatasetService datasetService;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private OntologyPreparseService preparseService;
    @Mock
    private ImportWorkerDispatcher importWorkerDispatcher;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private OntologyExportJobService exportJobService;

    @Mock
    private OntologyMutationService ontologyMutationService;
    @Mock
    private ProjectOntologyCache ontologyCache;
    @Mock
    private HierarchyIndexService hierarchyIndexService;
    @Mock
    private OntologyQueryService ontologyQueryService;

    private ProjectLoadController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        self.research.ontology.owlEditor.service.CodeViewReimportPipeline codeViewReimportPipeline =
                new self.research.ontology.owlEditor.service.CodeViewReimportPipeline(
                        storageManager, datasetService, metadataService, historyService, draftTrackingService);
        org.springframework.test.util.ReflectionTestUtils.setField(
                codeViewReimportPipeline, "ontologyMutationService", ontologyMutationService);
        org.springframework.test.util.ReflectionTestUtils.setField(
                codeViewReimportPipeline, "ontologyCache", ontologyCache);
        org.springframework.test.util.ReflectionTestUtils.setField(
                codeViewReimportPipeline, "hierarchyIndexService", hierarchyIndexService);
        org.springframework.test.util.ReflectionTestUtils.setField(
                codeViewReimportPipeline, "ontologyQueryService", ontologyQueryService);
        self.research.ontology.owlEditor.service.ProjectWriteLockRegistry lockRegistry =
                new self.research.ontology.owlEditor.service.ProjectWriteLockRegistry();
        controller = new ProjectLoadController(
                storageManager,
                metadataService,
                importService,
                gridFSFileService,
                shareService,
                draftTrackingService,
                draftCopyService,
                historyService,
                draftChangeRepository,
                messagingTemplate,
                datasetService,
                projectRepository,
                preparseService,
                importWorkerDispatcher,
                mongoTemplate,
                exportJobService,
                codeViewReimportPipeline,
                lockRegistry);
        ReflectionTestUtils.setField(controller, "ontologyCache", ontologyCache);
        ReflectionTestUtils.setField(controller, "hierarchyIndexService", hierarchyIndexService);
        ReflectionTestUtils.setField(controller, "ontologyQueryService", ontologyQueryService);

        when(storageManager.extensionFor(anyString())).thenAnswer(invocation -> {
            String format = invocation.getArgument(0);
            return switch (format.toLowerCase()) {
                case "turtle", "ttl" -> "ttl";
                case "ntriples", "nt" -> "nt";
                case "rdfxml", "xml" -> "owl";
                case "owlxml" -> "owlxml";
                case "manchester", "manchestersyntax" -> "omn";
                case "functional", "functionalsyntax" -> "ofn";
                default -> format.toLowerCase();
            };
        });
    }

    @Test
    void happyPathTurtleSaveReimportsAndSyncsCaches() throws Exception {
        String projectId = "proj-turtle";
        when(storageManager.getPublicGraphVersion(projectId)).thenReturn(7L);

        Map<String, Object> request = new HashMap<>();
        request.put("content", "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n<http://example.org/onto> a owl:Ontology .");

        ResponseEntity<Map<String, Object>> response =
                controller.saveCodeViewAndSync(projectId, null, null, false, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals(true, body.get("success"));
        assertEquals("turtle", body.get("format"));
        assertEquals(7L, body.get("sourceVersion"));

        verify(datasetService).bulkLoadChunked(eq(projectId), any(InputStream.class), eq(RDFFormat.TURTLE),
                anyLong(), any(ImportOptions.class), isNull(), isNull());
        verify(metadataService).incrementMutationVersion(projectId);
        verify(storageManager).clearCodeViewCache(projectId);
        verify(storageManager).storeCodeViewCache(eq(projectId), any(), eq("turtle"));
    }

    @Test
    void happyPathOwlApiFormatSaveCachesOriginalRawContent() throws Exception {
        String projectId = "proj-functional";
        String originalContent = "Prefix(:=<http://example.org/onto#>)\nOntology(<http://example.org/onto>)";
        Path convertedFile = Files.createTempFile("codeview-test-converted-", ".owl");
        Files.writeString(convertedFile,
                "<?xml version=\"1.0\"?><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"></rdf:RDF>");

        try (MockedStatic<OWLFormatConverter> mockedConverter = mockStatic(OWLFormatConverter.class)) {
            mockedConverter.when(() -> OWLFormatConverter.convertToRDFXML(any(Path.class))).thenReturn(convertedFile);

            Map<String, Object> request = new HashMap<>();
            request.put("content", originalContent);
            request.put("format", "functional");

            ResponseEntity<Map<String, Object>> response =
                    controller.saveCodeViewAndSync(projectId, null, null, false, request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            verify(datasetService).bulkLoadChunked(eq(projectId), any(InputStream.class), eq(RDFFormat.RDFXML),
                    anyLong(), any(ImportOptions.class), isNull(), isNull());
            verify(storageManager).storeCodeViewCache(eq(projectId), eq(originalContent), eq("functional"));
        } finally {
            Files.deleteIfExists(convertedFile);
        }
    }

    @Test
    void expectedSourceVersionMismatchAsIntegerReturnsConflict() {
        String projectId = "proj-conflict-int";
        when(storageManager.getPublicGraphVersion(projectId)).thenReturn(5L);

        Map<String, Object> request = new HashMap<>();
        request.put("content", "@prefix : <http://example.org/> .");
        request.put("expectedSourceVersion", 3);

        ResponseEntity<Map<String, Object>> response =
                controller.saveCodeViewAndSync(projectId, null, null, false, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals(false, body.get("success"));
        assertEquals(true, body.get("conflictBlocked"));
        verify(datasetService, never()).bulkLoadChunked(any(), any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    void expectedSourceVersionMismatchAsLongReturnsConflict() {
        String projectId = "proj-conflict-long";
        when(storageManager.getPublicGraphVersion(projectId)).thenReturn(5L);

        Map<String, Object> request = new HashMap<>();
        request.put("content", "@prefix : <http://example.org/> .");
        request.put("expectedSourceVersion", 3L);

        ResponseEntity<Map<String, Object>> response =
                controller.saveCodeViewAndSync(projectId, null, null, false, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(true, response.getBody().get("conflictBlocked"));
        verify(datasetService, never()).bulkLoadChunked(any(), any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    void missingExpectedSourceVersionSkipsConflictCheck() {
        String projectId = "proj-no-version-check";
        when(storageManager.getPublicGraphVersion(projectId)).thenReturn(999L);

        Map<String, Object> request = new HashMap<>();
        request.put("content", "@prefix : <http://example.org/> .");

        ResponseEntity<Map<String, Object>> response =
                controller.saveCodeViewAndSync(projectId, null, null, false, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(datasetService).bulkLoadChunked(eq(projectId), any(InputStream.class), eq(RDFFormat.TURTLE),
                anyLong(), any(ImportOptions.class), isNull(), isNull());
    }

    @Test
    void draftSaveWithNullUserIdReturnsBadRequest() {
        Map<String, Object> request = new HashMap<>();
        request.put("content", "@prefix : <http://example.org/> .");

        ResponseEntity<Map<String, Object>> response =
                controller.saveCodeViewAndSync("proj-draft-no-user", null, null, true, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(false, response.getBody().get("success"));
    }

    @Test
    void draftSaveWithBlankUserIdReturnsBadRequest() {
        Map<String, Object> request = new HashMap<>();
        request.put("content", "@prefix : <http://example.org/> .");

        ResponseEntity<Map<String, Object>> response =
                controller.saveCodeViewAndSync("proj-draft-blank-user", "   ", null, true, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void draftSaveWithDraftNotReadyReturnsConflictWithoutReimport() {
        String projectId = "proj-draft-not-ready";
        String userId = "alice";
        when(draftCopyService.isReady(projectId, userId)).thenReturn(false);

        Map<String, Object> request = new HashMap<>();
        request.put("content", "@prefix : <http://example.org/> .");

        ResponseEntity<Map<String, Object>> response =
                controller.saveCodeViewAndSync(projectId, userId, null, true, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(false, response.getBody().get("success"));
        verify(datasetService, never()).bulkLoadChunked(any(), any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    void draftSaveWithReadyDraftUsesDraftGraphOverride() {
        String projectId = "proj-draft-ready";
        String userId = "alice";
        String draftGraphUri = "http://example.org/graphs/draft-alice";
        when(draftCopyService.isReady(projectId, userId)).thenReturn(true);
        when(datasetService.getDraftGraphUri(projectId, userId)).thenReturn(draftGraphUri);

        Map<String, Object> request = new HashMap<>();
        request.put("content", "@prefix : <http://example.org/> .");

        ResponseEntity<Map<String, Object>> response =
                controller.saveCodeViewAndSync(projectId, userId, null, true, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(datasetService).bulkLoadChunked(eq(projectId), any(InputStream.class), eq(RDFFormat.TURTLE),
                anyLong(), any(ImportOptions.class), isNull(), eq(draftGraphUri));
    }

    @Test
    void rdfXmlStructuralErrorTriggersReserializationRetryAndSucceeds() throws Exception {
        String projectId = "proj-rdfxml-retry";
        Path convertedFile = Files.createTempFile("codeview-test-retry-converted-", ".owl");
        Files.writeString(convertedFile,
                "<?xml version=\"1.0\"?><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"></rdf:RDF>");

        doThrow(new RuntimeException("Unexpected end of file"))
                .doNothing()
                .when(datasetService).bulkLoadChunked(eq(projectId), any(InputStream.class), eq(RDFFormat.RDFXML),
                        anyLong(), any(ImportOptions.class), isNull(), isNull());

        try (MockedStatic<OWLFormatConverter> mockedConverter = mockStatic(OWLFormatConverter.class)) {
            mockedConverter.when(() -> OWLFormatConverter.convertToRDFXML(any(Path.class))).thenReturn(convertedFile);

            Map<String, Object> request = new HashMap<>();
            request.put("content", "<rdf:RDF><this is not well formed");
            request.put("format", "rdfxml");

            ResponseEntity<Map<String, Object>> response =
                    controller.saveCodeViewAndSync(projectId, null, null, false, request);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            verify(datasetService, times(2)).bulkLoadChunked(eq(projectId), any(InputStream.class), eq(RDFFormat.RDFXML),
                    anyLong(), any(ImportOptions.class), isNull(), isNull());
        } finally {
            Files.deleteIfExists(convertedFile);
        }
    }

    @Test
    void nonStructuralRdfXmlErrorPropagatesAsServerErrorWithoutClearingCache() {
        String projectId = "proj-rdfxml-fatal";
        doThrow(new RuntimeException("GraphDB connection refused"))
                .when(datasetService).bulkLoadChunked(eq(projectId), any(InputStream.class), eq(RDFFormat.RDFXML),
                        anyLong(), any(ImportOptions.class), isNull(), isNull());

        Map<String, Object> request = new HashMap<>();
        request.put("content", "<rdf:RDF></rdf:RDF>");
        request.put("format", "rdfxml");

        ResponseEntity<Map<String, Object>> response =
                controller.saveCodeViewAndSync(projectId, null, null, false, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals(false, body.get("success"));
        assertTrue(((String) body.get("error")).contains("GraphDB connection refused"));
        verify(storageManager, never()).clearCodeViewCache(any());
    }

    @Test
    void concurrentSaveCallsForSameProjectAreSerializedByPerProjectLock() throws Exception {
        String projectId = "proj-same-project-lock";
        List<long[]> intervals = new CopyOnWriteArrayList<>();

        org.mockito.Mockito.doAnswer(invocation -> {
            long start = System.nanoTime();
            Thread.sleep(150);
            intervals.add(new long[]{start, System.nanoTime()});
            return null;
        }).when(datasetService).bulkLoadChunked(eq(projectId), any(InputStream.class), eq(RDFFormat.TURTLE),
                anyLong(), any(ImportOptions.class), isNull(), isNull());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<ResponseEntity<Map<String, Object>>> responses = new CopyOnWriteArrayList<>();

        Runnable task = () -> {
            try {
                start.await();
                Map<String, Object> request = new HashMap<>();
                request.put("content", "@prefix : <http://example.org/> .");
                responses.add(controller.saveCodeViewAndSync(projectId, null, null, false, request));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        };

        pool.submit(task);
        pool.submit(task);
        start.countDown();
        boolean finished = done.await(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertTrue(finished);
        assertEquals(2, responses.size());
        for (ResponseEntity<Map<String, Object>> response : responses) {
            assertEquals(HttpStatus.OK, response.getStatusCode());
        }
        assertEquals(2, intervals.size());
        long[] first = intervals.get(0);
        long[] second = intervals.get(1);
        boolean nonOverlapping = first[1] <= second[0] || second[1] <= first[0];
        assertTrue(nonOverlapping);
    }
}
