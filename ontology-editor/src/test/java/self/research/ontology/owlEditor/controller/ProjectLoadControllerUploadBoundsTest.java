package self.research.ontology.owlEditor.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.repository.DraftChangeRepository;
import self.research.ontology.owlEditor.repository.ProjectRepository;
import self.research.ontology.owlEditor.service.CodeViewReimportPipeline;
import self.research.ontology.owlEditor.service.DraftCopyService;
import self.research.ontology.owlEditor.service.DraftTrackingService;
import self.research.ontology.owlEditor.service.GridFSFileService;
import self.research.ontology.owlEditor.service.ImportQueueFullException;
import self.research.ontology.owlEditor.service.ImportWorkerDispatcher;
import self.research.ontology.owlEditor.service.OntologyExportJobService;
import self.research.ontology.owlEditor.service.OntologyHistoryService;
import self.research.ontology.owlEditor.service.OntologyPreparseService;
import self.research.ontology.owlEditor.service.ProjectImportService;
import self.research.ontology.owlEditor.service.ProjectMetadataService;
import self.research.ontology.owlEditor.service.ProjectShareService;
import self.research.ontology.owlEditor.service.ProjectWriteLockRegistry;
import self.research.ontology.owlEditor.service.SparqlDatasetService;
import self.research.ontology.owlEditor.service.StorageManager;
import self.research.ontology.owlEditor.upload.UploadSettings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectLoadControllerUploadBoundsTest {

    @TempDir
    Path projectDir;

    private StorageManager storageManager;
    private GridFSFileService gridFs;
    private ImportWorkerDispatcher dispatcher;
    private ProjectLoadController controller;

    @BeforeEach
    void setUp() throws Exception {
        storageManager = mock(StorageManager.class);
        gridFs = mock(GridFSFileService.class);
        dispatcher = mock(ImportWorkerDispatcher.class);
        when(storageManager.prepareProjectDir(any())).thenReturn(projectDir);
        when(gridFs.storeFile(any(), any(), any(), any())).thenAnswer(inv -> {
            try (InputStream in = inv.getArgument(3)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return "gridfs-1";
        });
        controller = new ProjectLoadController(storageManager, mock(ProjectMetadataService.class),
                mock(ProjectImportService.class), gridFs, mock(ProjectShareService.class),
                mock(DraftTrackingService.class), mock(DraftCopyService.class), mock(OntologyHistoryService.class),
                mock(DraftChangeRepository.class), mock(SimpMessagingTemplate.class), mock(SparqlDatasetService.class),
                mock(ProjectRepository.class), mock(OntologyPreparseService.class), dispatcher,
                mock(MongoTemplate.class), mock(OntologyExportJobService.class), mock(CodeViewReimportPipeline.class),
                new ProjectWriteLockRegistry(),
                mock(self.research.ontology.owlEditor.service.ProjectAuthorization.class));
        ReflectionTestUtils.setField(controller, "uploadSettings", new UploadSettings(new MockEnvironment()
                .withProperty("ontocode.capacity.upload.archive.zip-max-entries", "3")
                .withProperty("ontocode.capacity.upload.archive.gzip-max-ratio", "50")));
    }

    private ResponseEntity<Map<String, Object>> upload(String name, byte[] body) {
        return controller.upload("proj-1", new MockMultipartFile("file", name, "application/octet-stream", body),
                null, new self.research.ontology.owlEditor.config.CallerIdentity(null, null), null, null, null, null, null, false);
    }

    private static byte[] zip(String... names) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (String n : names) {
                z.putNextEntry(new ZipEntry(n));
                z.write("<rdf:RDF/>".getBytes());
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private void assertNothingLeftBehind() {
        assertFalse(Files.exists(projectDir.resolve("ontology-package.zip")));
        assertFalse(Files.exists(projectDir.resolve("ontology-library")));
        assertFalse(Files.exists(projectDir.resolve("ontology.original.owl")));
    }

    @Test
    void packageWithTooManyEntriesGets413AndLeavesNoStagedFiles() throws Exception {
        ResponseEntity<Map<String, Object>> r = upload("pkg.zip", zip("a.owl", "b.owl", "c.owl", "d.owl", "e.owl"));
        assertEquals(413, r.getStatusCode().value());
        assertTrue(String.valueOf(r.getBody().get("error")).contains("more than 3 files"));
        assertNothingLeftBehind();
    }

    @Test
    void zipSlipPackageFailsAndLeavesNoStagedFiles() throws Exception {
        ResponseEntity<Map<String, Object>> r = upload("pkg.zip", zip("../evil.owl"));
        assertEquals(500, r.getStatusCode().value());
        assertTrue(String.valueOf(r.getBody().get("error")).contains("Unsafe"));
        assertNothingLeftBehind();
        assertFalse(Files.exists(projectDir.getParent().resolve("evil.owl")));
    }

    @Test
    void gzipBombGets413AndTheHalfWrittenFileIsRemoved() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(new byte[48 * 1024 * 1024]);
        }
        ResponseEntity<Map<String, Object>> r = upload("big.owl", bos.toByteArray());
        assertEquals(413, r.getStatusCode().value());
        assertTrue(String.valueOf(r.getBody().get("error")).contains("times"));
        assertNothingLeftBehind();
    }

    @Test
    void fullImportQueueGives429WithTheBusyBody() throws Exception {
        doThrow(new ImportQueueFullException("import_queue_full", 30, "Queue is full"))
                .when(dispatcher).dispatch(any(), any(), any(), any(), any(), any());
        ResponseEntity<Map<String, Object>> r = upload("o.owl", "<rdf:RDF xmlns:rdf=\"x\"/>".getBytes());
        assertEquals(429, r.getStatusCode().value());
        assertEquals(true, r.getBody().get("busy"));
        assertEquals("import_queue_full", r.getBody().get("reason"));
        assertEquals(30, r.getBody().get("retryAfterSeconds"));
    }
}
