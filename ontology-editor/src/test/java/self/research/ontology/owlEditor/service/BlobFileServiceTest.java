package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.common.db.BlobStore;
import self.research.ontology.common.db.DiskBlobStore;
import self.research.ontology.common.db.StoredFiles;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.owlEditor.TestDatabase;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlobFileServiceTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();

    @TempDir
    Path root;

    private BlobStore store;
    private BlobFileService service;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from stored_file");
        store = new DiskBlobStore(new StoredFiles(jdbc, new ObjectMapper()), root, 0);
        service = new BlobFileService(store);
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(BlobResource resource) throws Exception {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aStoredFileKeepsA24HexIdAndIsReadableByIdAndByProject() throws Exception {
        String id = service.storeFile("proj-1--f1", "pizza.owl", "application/rdf+xml", stream("<owl/>"));

        assertTrue(Ids.looksLikeObjectId(id), id);
        assertEquals("<owl/>", read(service.getFileById(id).orElseThrow()));
        BlobResource byProject = service.getFileByProjectId("proj-1--f1").orElseThrow();
        assertEquals(id, byProject.getId());
        assertEquals("pizza.owl", byProject.getFilename());
        assertEquals(6L, byProject.contentLength());
        assertEquals("fs", store.info(id).orElseThrow().bucket());
        assertEquals("proj-1--f1", store.info(id).orElseThrow().metadata().get("projectId"));
        assertTrue(service.fileExistsForProject("proj-1--f1"));
        assertFalse(service.fileExistsForProject("proj-2"));
    }

    @Test
    void theNewestFileOfAProjectWins() throws Exception {
        service.storeFile("proj-1", "old.owl", "text/plain", stream("old"));
        Thread.sleep(20);
        String newest = service.storeFile("proj-1", "new.owl", "text/plain", stream("new"));

        assertEquals(newest, service.getFileByProjectId("proj-1").orElseThrow().getId());
        assertEquals(newest, service.getFileMetadataByProjectId("proj-1").orElseThrow().id());
        assertEquals("new", read(service.getFileByProjectId("proj-1").orElseThrow()));
    }

    @Test
    void missingBlankAndMalformedIdsAreJustNotFound() {
        assertTrue(service.getFileById(null).isEmpty());
        assertTrue(service.getFileById(" ").isEmpty());
        assertTrue(service.getFileById("not-an-id").isEmpty());
        assertTrue(service.getFileByProjectId("none").isEmpty());
        assertTrue(service.getFileByName("none.owl").isEmpty());
    }

    @Test
    void filesCanBeFoundByNameAndDeletedByIdOrByProject() {
        String a = service.storeFile("proj-1", "a.owl", "text/plain", stream("a"));
        service.storeFile("proj-1", "b.owl", "text/plain", stream("b"));
        String other = service.storeFile("proj-2", "c.owl", "text/plain", stream("c"));

        assertEquals(a, service.getFileByName("a.owl").orElseThrow().getId());

        service.deleteFileById(a);
        assertTrue(service.getFileById(a).isEmpty());
        assertEquals(1, store.find("fs", null, Map.of("projectId", "proj-1")).size());

        service.deleteFileByProjectId("proj-1");
        assertTrue(service.getFileByProjectId("proj-1").isEmpty());
        assertEquals(other, service.getFileByProjectId("proj-2").orElseThrow().getId());
    }

    @Test
    void aDraftBaselineStoredUnderTheSameProjectIdIsNeverPickedUpOrDeletedAsTheOntologyFile() throws Exception {
        String file = service.storeFile("proj-1", "pizza.owl", "application/rdf+xml", stream("<owl/>"));
        new DraftBaselineStore(store).save("proj-1", "u1", "<baseline/>");

        assertEquals(file, service.getFileByProjectId("proj-1").orElseThrow().getId());
        assertEquals(file, service.getFileMetadataByProjectId("proj-1").orElseThrow().id());
        assertTrue(service.fileExistsForProject("proj-1"));

        service.deleteFileByProjectId("proj-1");

        assertFalse(service.fileExistsForProject("proj-1"));
        assertEquals("<baseline/>", new DraftBaselineStore(store).load("proj-1", "u1").orElseThrow());
    }

    @Test
    void aProjectWithOnlyABaselineHasNoOntologyFile() {
        new DraftBaselineStore(store).save("proj-9", "u1", "<baseline/>");

        assertFalse(service.fileExistsForProject("proj-9"));
        assertTrue(service.getFileByProjectId("proj-9").isEmpty());
    }

    @Test
    void filesStoredBeforeTheTypeMarkerExistedStillCount() {
        store.store("fs", "old.owl", "text/plain", Map.of("projectId", "proj-3"), stream("old"));

        assertTrue(service.fileExistsForProject("proj-3"));
    }
}
