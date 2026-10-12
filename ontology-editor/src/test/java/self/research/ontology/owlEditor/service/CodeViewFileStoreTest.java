package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeViewFileStoreTest {

    @Mock
    private SparqlDatasetService datasetService;

    @TempDir
    Path tempDir;

    private CodeViewFileStore store;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        OntologyExporter exporter = new OntologyExporter(datasetService, this::projectDir, mock(CitationPositions.class));
        store = new CodeViewFileStore(this::projectDir, exporter);
    }

    private Path projectDir(String projectId) {
        return tempDir.resolve(projectId);
    }

    @Test
    void secondReadForSameUserReusesTheCachedExport() throws Exception {
        when(datasetService.exportDraftGraphContent("p1", "u1", RDFFormat.TURTLE))
                .thenReturn(":A a :B .");

        Path first = store.ensureDraftCodeViewFile("p1", "u1", "turtle");
        Path second = store.ensureDraftCodeViewFile("p1", "u1", "turtle");

        assertEquals(first, second);
        assertEquals(":A a :B .", Files.readString(second));
        verify(datasetService, times(1)).exportDraftGraphContent("p1", "u1", RDFFormat.TURTLE);
    }

    @Test
    void differentUsersGetIsolatedCacheEntries() throws Exception {
        when(datasetService.exportDraftGraphContent("p1", "u1", RDFFormat.TURTLE)).thenReturn(":A a :B .");
        when(datasetService.exportDraftGraphContent("p1", "u2", RDFFormat.TURTLE)).thenReturn(":C a :D .");

        Path forU1 = store.ensureDraftCodeViewFile("p1", "u1", "turtle");
        Path forU2 = store.ensureDraftCodeViewFile("p1", "u2", "turtle");

        assertNotEquals(forU1, forU2);
        assertEquals(":A a :B .", Files.readString(forU1));
        assertEquals(":C a :D .", Files.readString(forU2));
    }

    @Test
    void bumpDraftGraphVersionInvalidatesOnlyThatUsersCache() throws Exception {
        when(datasetService.exportDraftGraphContent("p1", "u1", RDFFormat.TURTLE))
                .thenReturn(":A a :B .")
                .thenReturn(":A a :B ; :changed true .");
        when(datasetService.exportDraftGraphContent("p1", "u2", RDFFormat.TURTLE)).thenReturn(":C a :D .");

        store.ensureDraftCodeViewFile("p1", "u1", "turtle");
        store.ensureDraftCodeViewFile("p1", "u2", "turtle");

        store.bumpDraftGraphVersion("p1", "u1");

        Path u1AfterBump = store.ensureDraftCodeViewFile("p1", "u1", "turtle");
        Path u2AfterBump = store.ensureDraftCodeViewFile("p1", "u2", "turtle");

        assertEquals(":A a :B ; :changed true .", Files.readString(u1AfterBump));
        verify(datasetService, times(2)).exportDraftGraphContent("p1", "u1", RDFFormat.TURTLE);
        verify(datasetService, times(1)).exportDraftGraphContent("p1", "u2", RDFFormat.TURTLE);
        assertEquals(":C a :D .", Files.readString(u2AfterBump));
    }
}
