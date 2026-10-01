package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.model.ImportOptions;
import self.research.ontology.owlEditor.service.CodeViewReimportPipeline.ReimportRequest;
import self.research.ontology.owlEditor.service.CodeViewReimportPipeline.ReimportResult;
import self.research.ontology.owlEditor.util.OWLFormatConverter;
import self.research.ontology.owlEditor.util.RdfFiles;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class CodeViewReimportPipelineTest {

    @Mock
    private StorageManager storageManager;

    @Mock
    private SparqlDatasetService datasetService;

    @Mock
    private ProjectMetadataService metadataService;

    @Mock
    private OntologyHistoryService historyService;

    @Mock
    private DraftTrackingService draftTrackingService;

    private CodeViewReimportPipeline pipeline;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        pipeline = new CodeViewReimportPipeline(storageManager, datasetService, metadataService,
                historyService, draftTrackingService);
        when(storageManager.extensionFor(anyString())).thenReturn("ttl");
        when(storageManager.getPublicGraphVersion("proj-1")).thenReturn(9L);
    }

    private Path fileWith(String extension, String content) throws Exception {
        Path file = Files.createTempFile("pipeline-test-", "." + extension);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void happyPathTurtleReimports() throws Exception {
        Path contentFile = fileWith("ttl", ":A a owl:Class .");

        ReimportResult result = pipeline.reimport(new ReimportRequest(
                "proj-1", "turtle", contentFile, false, "u1", "User", null, null, false));

        assertEquals(RDFFormat.TURTLE, result.rdfFormat());
        assertEquals(9L, result.sourceVersion());
        verify(datasetService).bulkLoadChunked(eq("proj-1"), any(InputStream.class), eq(RDFFormat.TURTLE),
                anyLong(), any(ImportOptions.class), isNull(), isNull());
        verify(metadataService).incrementMutationVersion("proj-1");
        verify(storageManager).clearCodeViewCache("proj-1");
        verify(storageManager).storeCodeViewCache(eq("proj-1"), any(), eq("turtle"));
    }

    @Test
    void happyPathOwlApiFormatCachesRawOriginalContent() throws Exception {
        when(storageManager.extensionFor("functional")).thenReturn("owl");
        String rawContent = "Prefix(:=<http://example.org/onto#>)\nOntology(<http://example.org/onto>)";
        Path contentFile = fileWith("owl", rawContent);
        Path convertedFile = Files.createTempFile("test-converted-", ".owl");
        Files.writeString(convertedFile,
                "<?xml version=\"1.0\"?><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"></rdf:RDF>",
                StandardCharsets.UTF_8);

        try (MockedStatic<OWLFormatConverter> mocked = mockStatic(OWLFormatConverter.class)) {
            mocked.when(() -> OWLFormatConverter.convertToRDFXML(any(Path.class))).thenReturn(convertedFile);

            ReimportResult result = pipeline.reimport(new ReimportRequest(
                    "proj-1", "functional", contentFile, false, "u1", "User", null, null, false));

            assertEquals(RDFFormat.RDFXML, result.rdfFormat());
            verify(storageManager).storeCodeViewCache(eq("proj-1"), eq(rawContent), eq("functional"));
        }
    }

    @Test
    void structuralXmlErrorTriggersRetryAndSucceeds() throws Exception {
        when(storageManager.extensionFor("rdfxml")).thenReturn("rdf");
        Path contentFile = fileWith("rdf", "<rdf:RDF></rdf:RDF>");
        Path retryConverted = Files.createTempFile("test-retry-converted-", ".rdf");
        Files.writeString(retryConverted,
                "<?xml version=\"1.0\"?><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"></rdf:RDF>",
                StandardCharsets.UTF_8);

        doThrow(new RuntimeException("XML document structures must be terminated"))
                .doNothing()
                .when(datasetService).bulkLoadChunked(anyString(), any(InputStream.class), eq(RDFFormat.RDFXML),
                        anyLong(), any(ImportOptions.class), isNull(), isNull());

        try (MockedStatic<OWLFormatConverter> mocked = mockStatic(OWLFormatConverter.class)) {
            mocked.when(() -> OWLFormatConverter.sanitizeFileOnDisk(any(Path.class))).thenAnswer(inv -> null);
            mocked.when(() -> OWLFormatConverter.convertToRDFXML(any(Path.class))).thenReturn(retryConverted);

            ReimportResult result = pipeline.reimport(new ReimportRequest(
                    "proj-1", "rdfxml", contentFile, false, "u1", "User", null, null, false));

            assertEquals(RDFFormat.RDFXML, result.rdfFormat());
            verify(datasetService, times(2)).bulkLoadChunked(anyString(), any(InputStream.class),
                    eq(RDFFormat.RDFXML), anyLong(), any(ImportOptions.class), isNull(), isNull());
        }
    }

    @Test
    void genericBulkLoadFailureNeverClearsCache() throws Exception {
        when(storageManager.extensionFor("turtle")).thenReturn("ttl");
        Path contentFile = fileWith("ttl", ":A a owl:Class .");
        doThrow(new RuntimeException("connection refused"))
                .when(datasetService).bulkLoadChunked(anyString(), any(InputStream.class), eq(RDFFormat.TURTLE),
                        anyLong(), any(ImportOptions.class), isNull(), isNull());

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () ->
                pipeline.reimport(new ReimportRequest("proj-1", "turtle", contentFile,
                        false, "u1", "User", null, null, false)));

        verify(storageManager, never()).clearCodeViewCache(anyString());
    }

    @Test
    void skipSanitizationTrueNeverCallsOwlApiReserialization() throws Exception {
        Path contentFile = fileWith("ttl", ":A a owl:Class .");

        try (MockedStatic<OWLFormatConverter> mocked = mockStatic(OWLFormatConverter.class)) {
            pipeline.reimport(new ReimportRequest("proj-1", "turtle", contentFile,
                    false, "u1", "User", null, null, true));

            mocked.verify(() -> OWLFormatConverter.sanitizeFileOnDisk(any(Path.class)), never());
        }
    }

    @Test
    void skipSanitizationFalseCallsOwlApiReserializationAsBefore() throws Exception {
        Path contentFile = fileWith("ttl", ":A a owl:Class .");

        try (MockedStatic<OWLFormatConverter> mocked = mockStatic(OWLFormatConverter.class)) {
            pipeline.reimport(new ReimportRequest("proj-1", "turtle", contentFile,
                    false, "u1", "User", null, null, false));

            mocked.verify(() -> OWLFormatConverter.sanitizeFileOnDisk(any(Path.class)));
        }
    }

    @Test
    void unsanitizedImportReportsThatTheCacheMatchesTheSubmittedFile() throws Exception {
        Path contentFile = fileWith("ttl", ":A a owl:Class .");

        try (MockedStatic<OWLFormatConverter> ignored = mockStatic(OWLFormatConverter.class)) {
            CodeViewReimportPipeline.ReimportResult result = pipeline.reimport(new ReimportRequest("proj-1", "turtle",
                    contentFile, false, "u1", "User", null, null, true));

            assertTrue(result.cacheMatchesSubmittedContent());
        }
    }

    @Test
    void sanitizedImportDoesNotClaimTheCacheMatchesTheSubmittedFile() throws Exception {
        Path contentFile = fileWith("ttl", ":A a owl:Class .");

        try (MockedStatic<OWLFormatConverter> ignored = mockStatic(OWLFormatConverter.class)) {
            CodeViewReimportPipeline.ReimportResult result = pipeline.reimport(new ReimportRequest("proj-1", "turtle",
                    contentFile, false, "u1", "User", null, null, false));

            assertFalse(result.cacheMatchesSubmittedContent());
        }
    }

    @Test
    void snapshotsAreRestoredInTheFormatTheirExtensionNames() {
        assertEquals(RDFFormat.NTRIPLES, RdfFiles.snapshotFormat(Path.of("op-1.nt")));
        assertEquals(RDFFormat.NTRIPLES, RdfFiles.snapshotFormat(Path.of("OP-1.NT")));
        assertEquals(RDFFormat.RDFXML, RdfFiles.snapshotFormat(Path.of("op-1.owl")));
    }

    @Test
    void restoringAnNTriplesSnapshotLoadsItAsNTriples() throws Exception {
        Path snapshot = Files.createTempFile("restore-", ".nt");
        Files.writeString(snapshot, "<urn:a> <urn:b> <urn:c> .\n", StandardCharsets.UTF_8);

        pipeline.restoreSnapshot("proj-1", snapshot);

        verify(datasetService).bulkLoadChunked(eq("proj-1"), any(InputStream.class), eq(RDFFormat.NTRIPLES),
                anyLong(), any(ImportOptions.class), isNull(), isNull());
    }

    @Test
    void draftModePassesTargetGraphOverrideThrough() throws Exception {
        when(storageManager.extensionFor("turtle")).thenReturn("ttl");
        Path contentFile = fileWith("ttl", ":A a owl:Class .");

        pipeline.reimport(new ReimportRequest("proj-1", "turtle", contentFile,
                true, "u1", "User", "urn:draft:graph:u1", null, false));

        verify(datasetService).bulkLoadChunked(eq("proj-1"), any(InputStream.class), eq(RDFFormat.TURTLE),
                anyLong(), any(ImportOptions.class), isNull(), eq("urn:draft:graph:u1"));
    }

    @Test
    void oldContentFileForDiffTriggersDiffRecording() throws Exception {
        when(storageManager.extensionFor("turtle")).thenReturn("ttl");
        Path contentFile = fileWith("ttl", ":A a owl:Class .");
        Path oldFile = Files.createTempFile("pipeline-old-", ".rdf");
        Files.writeString(oldFile,
                "<?xml version=\"1.0\"?><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"></rdf:RDF>",
                StandardCharsets.UTF_8);

        pipeline.reimport(new ReimportRequest("proj-1", "turtle", contentFile,
                false, "u1", "User", null, oldFile, false));

        verify(metadataService).incrementMutationVersion("proj-1");
    }

    @Test
    void restoreSnapshotReloadsTheGraphAsRdfXmlAndInvalidatesCaches() throws Exception {
        Path snapshot = fileWith("owl",
                "<?xml version=\"1.0\"?><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"></rdf:RDF>");

        long version = pipeline.restoreSnapshot("proj-1", snapshot);

        assertEquals(9L, version);
        verify(datasetService).bulkLoadChunked(eq("proj-1"), any(InputStream.class), eq(RDFFormat.RDFXML),
                eq(Files.size(snapshot)), any(ImportOptions.class), isNull(), isNull());
        verify(datasetService).markProjectDirty("proj-1");
        verify(metadataService).incrementMutationVersion("proj-1");
        verify(storageManager).clearCodeViewCache("proj-1");
        verify(storageManager, never()).storeCodeViewCache(anyString(), any(), anyString());
        verify(historyService, never()).recordEdit(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void restoreSnapshotWithMissingFileFailsWithoutTouchingTheGraph() {
        Path missing = Path.of(System.getProperty("java.io.tmpdir"), "does-not-exist-" + System.nanoTime() + ".owl");

        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> pipeline.restoreSnapshot("proj-1", missing));

        verify(datasetService, never()).bulkLoadChunked(anyString(), any(InputStream.class), any(RDFFormat.class),
                anyLong(), any(ImportOptions.class), any(), any());
        verify(metadataService, never()).incrementMutationVersion(anyString());
    }

    @Test
    void restoreSnapshotPropagatesAGraphFailureAndLeavesCachesAlone() throws Exception {
        Path snapshot = fileWith("owl", "<rdf:RDF/>");
        doThrow(new RuntimeException("GraphDB down")).when(datasetService).bulkLoadChunked(anyString(),
                any(InputStream.class), any(RDFFormat.class), anyLong(), any(ImportOptions.class), any(), any());

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> pipeline.restoreSnapshot("proj-1", snapshot));

        verify(metadataService, never()).incrementMutationVersion(anyString());
        verify(storageManager, never()).clearCodeViewCache(anyString());
    }

    private final Deque<Runnable> queuedRefreshes = new ArrayDeque<>();

    private OntologyIndexService withMetadataRefresh() {
        OntologyIndexService indexService = org.mockito.Mockito.mock(OntologyIndexService.class);
        ReflectionTestUtils.setField(pipeline, "indexService", indexService);
        ReflectionTestUtils.setField(pipeline, "metadataExecutor", (Executor) queuedRefreshes::add);
        return indexService;
    }

    @Test
    void aCodeViewSaveRefreshesTheCachedCountsBeforeItReturns() throws Exception {
        OntologyIndexService indexService = withMetadataRefresh();
        Map<String, Object> meta = Map.of("classes", 3);
        when(indexService.computeMetadata("proj-1")).thenReturn(meta);

        pipeline.reimport(new ReimportRequest("proj-1", "turtle", fileWith("ttl", ":A a owl:Class ."),
                false, "u1", "User", null, null, false));

        verify(metadataService).writeMeta("proj-1", meta);
        assertTrue(queuedRefreshes.isEmpty());
    }

    @Test
    void anAssistantApplyRefreshesTheCachedCountsInTheBackground() throws Exception {
        OntologyIndexService indexService = withMetadataRefresh();
        Map<String, Object> meta = Map.of("classes", 3);
        when(indexService.computeMetadata("proj-1")).thenReturn(meta);

        pipeline.reimport(new ReimportRequest("proj-1", "turtle", fileWith("ttl", ":A a owl:Class ."),
                false, "u1", "User", null, null, false, ChangeOrigin.ai("g1", "claude", "m", "s1", "add A")));

        verify(metadataService, never()).writeMeta(anyString(), any());
        assertEquals(1, queuedRefreshes.size());
        queuedRefreshes.poll().run();
        verify(metadataService).writeMeta("proj-1", meta);
    }

    @Test
    void anOlderBackgroundRefreshNeverOverwritesANewerOne() throws Exception {
        OntologyIndexService indexService = withMetadataRefresh();
        Map<String, Object> newer = Map.of("classes", 2);
        Map<String, Object> older = Map.of("classes", 1);
        when(indexService.computeMetadata("proj-1")).thenReturn(newer, older);
        Path snapshot = fileWith("owl",
                "<?xml version=\"1.0\"?><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"></rdf:RDF>");

        pipeline.restoreSnapshot("proj-1", snapshot);
        pipeline.restoreSnapshot("proj-1", snapshot);
        Runnable first = queuedRefreshes.poll();
        queuedRefreshes.poll().run();
        first.run();

        verify(metadataService).writeMeta("proj-1", newer);
        verify(metadataService, never()).writeMeta("proj-1", older);
    }
}
