package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesktopOntologyLoaderDraftRefreshTest {

    @Mock
    private StorageManager storageManager;
    @Mock
    private SparqlDatasetService datasetService;

    @TempDir
    Path projectDir;

    private DesktopOntologyLoader loader;
    private Path draft;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        loader = new DesktopOntologyLoader();
        ReflectionTestUtils.setField(loader, "storageManager", storageManager);
        ReflectionTestUtils.setField(loader, "datasetService", datasetService);
        ReflectionTestUtils.setField(loader, "owlApiFirst", true);
        draft = projectDir.resolve("draft").resolve("ontology.draft.owl");
        when(storageManager.draftOntologyPath("proj-1")).thenReturn(draft);
        when(storageManager.projectDir("proj-1")).thenReturn(projectDir);
    }

    @Test
    void draftIsReplacedWithWhatTheTripleStoreNowHolds() throws Exception {
        Files.createDirectories(draft.getParent());
        Files.writeString(draft, "<old draft without the assistant edit/>");
        doAnswer(inv -> {
            inv.<OutputStream>getArgument(2).write("<rdf:RDF with the assistant edit/>".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(datasetService).exportDatasetToStream(eq("proj-1"), eq(RDFFormat.RDFXML), any(OutputStream.class));

        loader.refreshDraftFromTripleStore("proj-1");

        assertEquals("<rdf:RDF with the assistant edit/>", Files.readString(draft));
    }

    @Test
    void aFailedExportRemovesTheOutdatedDraftSoItCanNotBeReloaded() throws Exception {
        Files.createDirectories(draft.getParent());
        Files.writeString(draft, "<old draft without the assistant edit/>");
        doThrow(new RuntimeException("triple store unreachable"))
                .when(datasetService).exportDatasetToStream(eq("proj-1"), eq(RDFFormat.RDFXML), any(OutputStream.class));

        loader.refreshDraftFromTripleStore("proj-1");

        assertFalse(Files.exists(draft));
    }

    @Test
    void aFailedExportKeepsTheDraftWhenTheTripleStoreIsBehindSoUnsavedEditsAreNotLost() throws Exception {
        Files.createDirectories(draft.getParent());
        Files.writeString(draft, "<unsaved manual edits/>");
        Files.writeString(projectDir.resolve("fuseki-sync-pending"), "pending");
        doThrow(new RuntimeException("triple store unreachable"))
                .when(datasetService).exportDatasetToStream(eq("proj-1"), eq(RDFFormat.RDFXML), any(OutputStream.class));

        loader.refreshDraftFromTripleStore("proj-1");

        assertEquals("<unsaved manual edits/>", Files.readString(draft));
    }

    @Test
    void outsideOwlApiFirstModeNothingIsWritten() {
        ReflectionTestUtils.setField(loader, "owlApiFirst", false);

        loader.refreshDraftFromTripleStore("proj-1");

        verify(datasetService, never()).exportDatasetToStream(anyString(), any(RDFFormat.class), any(OutputStream.class));
        assertFalse(Files.exists(draft));
    }
}
