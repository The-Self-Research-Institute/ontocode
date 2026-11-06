package self.research.ontology.owlEditor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.common.db.BlobStore;
import self.research.ontology.common.db.DiskBlobStore;
import self.research.ontology.common.db.StoredFiles;
import self.research.ontology.owlEditor.TestDatabase;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionControlServiceTest {

    private final JdbcTemplate jdbc = TestDatabase.jdbc();

    @TempDir
    Path root;

    private BlobStore store;
    private VersionControlService service;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from stored_file");
        store = new DiskBlobStore(new StoredFiles(jdbc, new ObjectMapper()), root, 0);
        service = new VersionControlService(store);
    }

    private static OWLOntology ontology() throws Exception {
        return OWLManager.createOWLOntologyManager().createOntology(IRI.create("http://example.org/o"));
    }

    @Test
    void versionsAreNumberedListedNewestFirstAndFoundById() throws Exception {
        VersionControlService.Version first = service.createVersion("p1", ontology(), "u1", "User", "first", "t1");
        Thread.sleep(5);
        VersionControlService.Version second = service.createVersion("p1", ontology(), "u1", "User", "second", null);
        service.createVersion("p2", ontology(), "u1", "User", "other project", null);

        assertEquals("1.0.0", first.getVersionNumber());
        assertEquals("1.0.1", second.getVersionNumber());
        List<VersionControlService.Version> versions = service.getVersions("p1");
        assertEquals(List.of(second.getId(), first.getId()), versions.stream().map(VersionControlService.Version::getId).toList());
        assertEquals("first", service.getVersion(first.getId()).getDescription());
        assertEquals("t1", service.getVersionByNumber("p1", "1.0.0").getTag());
        assertEquals(0, service.getVersion(first.getId()).getMetadata().get("classCount"));
        assertNotNull(service.getVersion(first.getId()).getCreatedAt());
    }

    @Test
    void anUnknownOrForeignIdIsNotAVersionAndCannotBeDeleted() throws Exception {
        String foreign = store.store("fs", "x.owl", "text/plain", Map.of("projectId", "p1"),
                new ByteArrayInputStream("x".getBytes())).id();

        assertNull(service.getVersion(foreign));
        assertNull(service.getVersion("nope"));
        assertFalse(service.deleteVersion(foreign, "u1"));
        assertTrue(store.info(foreign).isPresent());
    }

    @Test
    void deletingAVersionRemovesItAndStatisticsCountTheRest() throws Exception {
        VersionControlService.Version first = service.createVersion("p1", ontology(), "u1", "User", "first", null);
        service.createVersion("p1", ontology(), "u1", "User", "second", null);

        assertTrue(service.deleteVersion(first.getId(), "u1"));

        assertEquals(1, service.getVersions("p1").size());
        assertEquals(1, service.getVersionStatistics("p1").get("totalVersions"));
    }
}
