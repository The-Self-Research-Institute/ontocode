package self.research.ontology.common.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.common.db.testing.EmbeddedTestDatabase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskBlobStoreTest extends BlobStoreContract {

    private final JdbcTemplate jdbc = EmbeddedTestDatabase.jdbc();
    private final StoredFiles files = new StoredFiles(jdbc, new ObjectMapper());

    @TempDir
    Path root;

    private DiskBlobStore store;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from stored_file");
        store = new DiskBlobStore(files, root, 0);
    }

    @Override
    BlobStore store() {
        return store;
    }

    @Override
    JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    String expectedStorage() {
        return "disk";
    }

    @Override
    void assertNoPartialContent() {
        assertEquals(List.of(), regularFiles(root));
    }

    private static List<Path> regularFiles(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theContentSitsInABucketFolderNamedByTheIdAndTheRecordSaysDisk() {
        StoredFile stored = store.store("fs", "a", null, Map.of(), new ByteArrayInputStream("abc".getBytes()));

        Path expected = root.resolve("fs").resolve(stored.id().substring(0, 2)).resolve(stored.id().substring(2, 4)).resolve(stored.id());
        assertTrue(Files.isRegularFile(expected));
        assertEquals(3, expected.toFile().length());
        assertEquals("disk", stored.storage());
        assertEquals(0, jdbc.queryForObject("select count(*) from file_chunk", Integer.class));
    }

    @Test
    void deletingRemovesTheRecordAndTheFileFromDisk() {
        StoredFile stored = store.store("fs", "a", null, Map.of(), new ByteArrayInputStream("abc".getBytes()));
        assertEquals(1, regularFiles(root).size());

        assertTrue(store.delete(stored.id()));

        assertEquals(0, regularFiles(root).size());
        assertEquals(java.util.Optional.empty(), store.info(stored.id()));
    }

    @Test
    void ifSavingTheRecordFailsTheFileIsRemovedAgain() {
        StoredFiles failing = new StoredFiles(jdbc, new ObjectMapper()) {
            @Override
            public void upsert(String id, String bucket, String filename, String contentType, Map<String, Object> metadata,
                               long length, String sha256, String storage, Instant uploadDate) {
                throw new IllegalStateException("database down");
            }
        };
        DiskBlobStore broken = new DiskBlobStore(failing, root, 0);

        assertThrows(IllegalStateException.class,
                () -> broken.store("fs", "a", null, Map.of(), new ByteArrayInputStream("abc".getBytes())));

        assertEquals(List.of(), regularFiles(root));
    }

    @Test
    void storingIsRefusedWhenTheDiskWouldDropBelowTheFreeSpaceLimit() {
        DiskBlobStore tight = new DiskBlobStore(files, root, Long.MAX_VALUE);

        assertThrows(InsufficientStorageException.class,
                () -> tight.store("fs", "a", null, Map.of(), new ByteArrayInputStream("abc".getBytes())));

        assertEquals(List.of(), regularFiles(root));
        assertEquals(0, jdbc.queryForObject("select count(*) from stored_file", Integer.class));
    }

    @Test
    void readingAFileWhoseContentWentMissingFromDiskGivesAClearError() throws IOException {
        StoredFile stored = store.store("fs", "a", null, Map.of(), new ByteArrayInputStream("abc".getBytes()));
        Files.delete(regularFiles(root).get(0));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> store.open(stored.id()));

        assertTrue(e.getMessage().contains("missing from disk"));
    }

    @Test
    void unsafeBucketNamesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> store.store("../escape", "a", null, Map.of(), new ByteArrayInputStream("abc".getBytes())));
        assertThrows(IllegalArgumentException.class,
                () -> store.store("a/b", "a", null, Map.of(), new ByteArrayInputStream("abc".getBytes())));
    }

    @Test
    void theSweeperRemovesOldFilesWithNoRecordAndKeepsRecordedAndRecentOnes() throws IOException {
        StoredFile kept = store.store("fs", "kept", null, Map.of(), new ByteArrayInputStream("kept".getBytes()));
        Path folder = root.resolve("fs").resolve("ab").resolve("cd");
        Files.createDirectories(folder);
        Path oldStray = Files.writeString(folder.resolve("abcd00000000000000000001"), "stray");
        Path recentStray = Files.writeString(folder.resolve("abcd00000000000000000002"), "recent");
        Files.setLastModifiedTime(oldStray, FileTime.from(Instant.now().minus(Duration.ofHours(8))));
        Path keptFile = regularFiles(root).stream().filter(p -> p.getFileName().toString().equals(kept.id())).findFirst().orElseThrow();
        Files.setLastModifiedTime(keptFile, FileTime.from(Instant.now().minus(Duration.ofHours(8))));

        int removed = store.sweepOrphans(Duration.ofHours(6));

        assertEquals(1, removed);
        assertFalse(Files.exists(oldStray));
        assertTrue(Files.exists(recentStray));
        assertTrue(Files.exists(keptFile));
    }

    @Test
    void theSweeperAlsoRemovesLeftoverPartialUploads() throws IOException {
        Path temp = root.resolve(".tmp");
        Files.createDirectories(temp);
        Path part = Files.writeString(temp.resolve("deadbeef.part"), "half");
        Files.setLastModifiedTime(part, FileTime.from(Instant.now().minus(Duration.ofHours(8))));

        assertEquals(1, store.sweepOrphans(Duration.ofHours(6)));
        assertFalse(Files.exists(part));
    }
}
