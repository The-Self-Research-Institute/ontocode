package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.common.db.BlobStore;
import self.research.ontology.common.db.DiskBlobStore;
import self.research.ontology.common.db.StoredFile;
import self.research.ontology.common.db.StoredFiles;
import self.research.ontology.owlEditor.TestDatabase;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftBaselineStoreTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();

    @TempDir
    Path root;

    private BlobStore blobs;
    private DraftBaselineStore store;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from stored_file");
        blobs = new DiskBlobStore(new StoredFiles(jdbc, new ObjectMapper()), root, 0);
        store = new DraftBaselineStore(blobs);
    }

    @Test
    void savingKeepsOnlyTheNewestCopyForTheSameProjectAndUser() throws Exception {
        store.save("p1", "u1", "<first/>");
        Thread.sleep(20);
        store.save("p1", "u1", "<second/>");

        assertEquals(Optional.of("<second/>"), store.load("p1", "u1"));
        assertEquals(1, blobs.find("fs", "draft-baseline/p1/u1", null).size());
    }

    @Test
    void theStoredCopyCarriesTheDraftBaselineMetadata() {
        store.save("p1", "u1", "<rdf/>");

        StoredFile file = blobs.findLatest("fs", "draft-baseline/p1/u1", null).orElseThrow();
        assertEquals("application/rdf+xml", file.contentType());
        assertEquals(Map.of("type", "draft-baseline", "projectId", "p1", "userId", "u1"), file.metadata());
    }

    @Test
    void loadingReturnsNothingWhenThereIsNoCopy() {
        assertTrue(store.load("p1", "u1").isEmpty());
    }

    @Test
    void deletingRemovesOnlyThatUsersCopy() {
        store.save("p1", "u1", "<a/>");
        store.save("p1", "u2", "<b/>");

        store.delete("p1", "u1");

        assertTrue(store.load("p1", "u1").isEmpty());
        assertEquals(Optional.of("<b/>"), store.load("p1", "u2"));
    }

    @Test
    void aSavedBaselineWithNonAsciiTextComesBackUnchanged() {
        String text = "<rdf>café 中文</rdf>";
        store.save("p1", "u1", text);

        assertEquals(Optional.of(text), store.load("p1", "u1"));
    }

    @Test
    void anOlderCopyLeftByARaceIsRemovedByTheNextSaveAndNeverWins() throws Exception {
        store.save("p1", "u1", "<one/>");
        Thread.sleep(20);
        blobs.store("fs", "draft-baseline/p1/u1", "application/rdf+xml",
                Map.of("type", "draft-baseline", "projectId", "p1", "userId", "u1"),
                new ByteArrayInputStream("<raced/>".getBytes(StandardCharsets.UTF_8)));
        Thread.sleep(20);
        store.save("p1", "u1", "<three/>");

        assertEquals(Optional.of("<three/>"), store.load("p1", "u1"));
        assertEquals(1, blobs.find("fs", "draft-baseline/p1/u1", null).size());
    }
}
