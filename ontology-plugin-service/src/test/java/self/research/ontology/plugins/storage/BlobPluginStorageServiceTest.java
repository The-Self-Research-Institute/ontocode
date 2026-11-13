package self.research.ontology.plugins.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import self.research.ontology.common.db.DiskBlobStore;
import self.research.ontology.common.db.StoredFiles;
import self.research.ontology.common.db.testing.EmbeddedTestDatabase;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlobPluginStorageServiceTest {

    @TempDir
    Path root;

    private BlobPluginStorageService storage;
    private DiskBlobStore blobs;

    @BeforeEach
    void setUp() {
        var jdbc = EmbeddedTestDatabase.jdbc();
        jdbc.update("delete from stored_file");
        blobs = new DiskBlobStore(new StoredFiles(jdbc, new ObjectMapper()), root, 0);
        storage = new BlobPluginStorageService(blobs);
    }

    private PluginMetadata meta() {
        return PluginMetadata.builder().pluginId("p1").version("1.0.0").author("alice").build();
    }

    @Test
    void uploadThenDownloadReturnsTheSameBytesAndMetadataIsReported() throws Exception {
        byte[] bundle = "console.log('hi')".getBytes();

        String id = storage.uploadPlugin(new ByteArrayInputStream(bundle), "p1-1.0.0.js", "application/javascript", meta());

        try (var in = storage.downloadPlugin(id)) {
            assertArrayEquals(bundle, in.readAllBytes());
        }
        StorageMetadata info = storage.getMetadata(id);
        assertEquals("p1-1.0.0.js", info.getFileName());
        assertEquals("application/javascript", info.getContentType());
        assertEquals(bundle.length, info.getFileSize());
        assertEquals("p1", info.getPluginMetadata().getPluginId());
        assertEquals("1.0.0", info.getPluginMetadata().getVersion());
        assertEquals("alice", info.getPluginMetadata().getAuthor());
        assertTrue(storage.exists(id));
    }

    @Test
    void deleteRemovesTheFileAndExistsThenSaysNo() {
        String id = storage.uploadPlugin(new ByteArrayInputStream("x".getBytes()), "x.js", "text/javascript", meta());

        storage.deletePlugin(id);

        assertFalse(storage.exists(id));
        assertThrows(StorageException.class, () -> storage.downloadPlugin(id));
    }

    @Test
    void unknownIdsAreReportedAsStorageErrorsNotCrashes() {
        assertFalse(storage.exists("000000000000000000000000"));
        assertThrows(StorageException.class, () -> storage.downloadPlugin("000000000000000000000000"));
        assertThrows(StorageException.class, () -> storage.getMetadata("000000000000000000000000"));
    }

    @Test
    void aFileInAnotherBucketIsNotTreatedAsAPluginFile() {
        var other = blobs.store("fs", "ontology.owl", null, java.util.Map.of("projectId", "p"), new ByteArrayInputStream("o".getBytes()));

        assertFalse(storage.exists(other.id()));
        assertThrows(StorageException.class, () -> storage.downloadPlugin(other.id()));
    }
}
