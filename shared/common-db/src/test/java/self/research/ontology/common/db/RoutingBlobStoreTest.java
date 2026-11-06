package self.research.ontology.common.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.common.db.testing.EmbeddedTestDatabase;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoutingBlobStoreTest {

    private final JdbcTemplate jdbc = EmbeddedTestDatabase.jdbc();
    private final StoredFiles files = new StoredFiles(jdbc, new ObjectMapper());
    private final JdbcBlobStore database = new JdbcBlobStore(jdbc, EmbeddedTestDatabase.transactions(), files);

    @TempDir
    Path root;

    private DiskBlobStore disk;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from stored_file");
        disk = new DiskBlobStore(files, root, 0);
    }

    @Test
    void newFilesGoToTheConfiguredKindAndOldFilesOfTheOtherKindStillReadAndDelete() throws Exception {
        StoredFile old = database.store("fs", "old", null, Map.of("projectId", "p"), new ByteArrayInputStream("old".getBytes()));
        RoutingBlobStore routing = new RoutingBlobStore(database, disk, "disk", files);

        StoredFile fresh = routing.store("fs", "new", null, Map.of("projectId", "p"), new ByteArrayInputStream("new".getBytes()));

        assertEquals("database", old.storage());
        assertEquals("disk", fresh.storage());
        try (var in = routing.open(old.id())) {
            assertArrayEquals("old".getBytes(), in.readAllBytes());
        }
        try (var in = routing.open(fresh.id())) {
            assertArrayEquals("new".getBytes(), in.readAllBytes());
        }
        assertEquals(2, routing.find("fs", null, Map.of("projectId", "p")).size());
        assertEquals(2, routing.deleteWhere("fs", null, Map.of("projectId", "p")));
        assertEquals(0, jdbc.queryForObject("select count(*) from file_chunk", Integer.class));
    }

    @Test
    void switchingTheConfiguredKindToDatabaseWritesIntoTheDatabaseAgain() {
        RoutingBlobStore routing = new RoutingBlobStore(database, disk, "database", files);

        assertEquals("database", routing.store("fs", "a", null, Map.of(), new ByteArrayInputStream("a".getBytes())).storage());
    }

    @Test
    void anUnknownKindIsRejectedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new RoutingBlobStore(database, disk, "tape", files));
    }
}
