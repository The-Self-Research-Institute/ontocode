package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.owlEditor.model.merge.MergeOptions;
import self.research.ontology.owlEditor.model.merge.MergeStrategy;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MergeKeepsLiveEditsTest {

    private static final String NS = "http://ex.org/";

    @Mock
    private SparqlDatasetService datasetService;
    @Mock
    private ProjectImportService importService;
    @Mock
    private StorageManager storageManager;
    @Mock
    private ProjectMetadataService metadataService;
    @Mock
    private OntologyHistoryService historyService;

    @TempDir
    Path dir;

    private OntologyMergeService service;
    private Path sourceFile;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        service = new OntologyMergeService(datasetService, importService, storageManager, metadataService, historyService);
        sourceFile = dir.resolve("source.owl");
        write(sourceFile, "source", "SourceOnly", false);
        when(storageManager.projectDir("proj")).thenReturn(dir);
        when(metadataService.readMeta(anyString())).thenReturn(Optional.empty());
        doNothing().when(metadataService).writeMeta(anyString(), any());
        when(importService.trySubmitImport(anyString(), any())).thenReturn(true);
    }

    private static void write(Path path, String ontologyName, String className, boolean turtleSafeName) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create(NS + ontologyName));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + "Shared"))));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + className))));
        try (OutputStream out = new FileOutputStream(path.toFile())) {
            manager.saveOntology(ontology, new RDFXMLDocumentFormat(), out);
        }
    }

    private Path storedFile(String name) throws Exception {
        Path stored = dir.resolve(name);
        write(stored, "target", "StoredOnly", false);
        when(storageManager.findCurrentOntology("proj")).thenReturn(Optional.of(stored));
        return stored;
    }

    private void merge(String targetFileName) throws Exception {
        MergeOptions options = new MergeOptions();
        options.setStrategy(MergeStrategy.SIMPLE_UNION);
        service.mergeOntologies("src", sourceFile, "proj", null, null, options, "u1", "User");
    }

    private MergeLiveSource desktopWithAnUnsavedEdit() {
        DesktopOwlApiMutationService desktop = mock(DesktopOwlApiMutationService.class);
        when(desktop.isAuthoritative("proj")).thenReturn(true);
        doAnswer(inv -> {
            write(inv.getArgument(1), "target", "UnsavedEdit", false);
            return true;
        }).when(desktop).exportLiveModel(eq("proj"), any(Path.class));
        MergeLiveSource live = new MergeLiveSource(storageManager, datasetService);
        ReflectionTestUtils.setField(live, "desktopMutation", desktop);
        return live;
    }

    private String contentOf(Path file) throws Exception {
        return Files.readString(file);
    }

    @Test
    void withoutALiveSourceAnUnsavedEditIsLost() throws Exception {
        Path stored = storedFile("target.owl");
        merge("target.owl");
        assertTrue(contentOf(stored).contains("StoredOnly"));
        assertFalse(contentOf(stored).contains("UnsavedEdit"));
    }

    @Test
    void anEditInTheLiveDesktopModelSurvivesTheMerge() throws Exception {
        Path stored = storedFile("target.owl");
        ReflectionTestUtils.setField(service, "liveSource", desktopWithAnUnsavedEdit());
        merge("target.owl");
        String result = contentOf(stored);
        assertTrue(result.contains("UnsavedEdit"), "the unsaved edit was dropped by the merge");
        assertTrue(result.contains("SourceOnly"), "the merged file's classes are missing");
    }

    @Test
    void aTurtleProjectStaysTurtleWhenMergedFromTheLiveModel() throws Exception {
        Path stored = storedFile("target.ttl");
        ReflectionTestUtils.setField(service, "liveSource", desktopWithAnUnsavedEdit());
        merge("target.ttl");
        Model parsed = Rio.parse(Files.newInputStream(stored), "", RDFFormat.TURTLE);
        assertTrue(parsed.toString().contains("UnsavedEdit"));
    }

    @Test
    void theTemporaryLiveCopyIsRemoved() throws Exception {
        storedFile("target.owl");
        ReflectionTestUtils.setField(service, "liveSource", desktopWithAnUnsavedEdit());
        merge("target.owl");
        try (var files = Files.list(dir)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith("merge-live-")));
        }
    }

    @Test
    void theServerGraphIsUsedWhenItIsNewerThanTheStoredFile() throws Exception {
        Path stored = storedFile("target.owl");
        Files.createFile(dir.resolve("ontology.dirty"));
        doAnswer(inv -> {
            Path temp = dir.resolve("graph-export.owl");
            write(temp, "target", "GraphEdit", false);
            Files.copy(temp, (OutputStream) inv.getArgument(2));
            return null;
        }).when(datasetService).exportDatasetToStream(eq("proj"), any(RDFFormat.class), any(OutputStream.class));
        ReflectionTestUtils.setField(service, "liveSource", new MergeLiveSource(storageManager, datasetService));
        merge("target.owl");
        assertTrue(contentOf(stored).contains("GraphEdit"));
    }

    @Test
    void theStoredFileIsUsedWhenNothingNewerExists() throws Exception {
        Path stored = storedFile("target.owl");
        ReflectionTestUtils.setField(service, "liveSource", new MergeLiveSource(storageManager, datasetService));
        merge("target.owl");
        assertTrue(contentOf(stored).contains("StoredOnly"));
    }
}
