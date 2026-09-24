package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import self.research.ontology.owlEditor.model.ImportOptions;
import self.research.ontology.owlEditor.service.CodeViewReimportPipeline.ReimportRequest;
import self.research.ontology.owlEditor.service.CodeViewReimportPipeline.ReimportResult;
import self.research.ontology.owlEditor.util.OWLFormatConverter;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
