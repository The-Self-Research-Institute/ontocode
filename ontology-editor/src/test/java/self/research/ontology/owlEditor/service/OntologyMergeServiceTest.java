package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import self.research.ontology.owlEditor.model.merge.MergeOptions;
import self.research.ontology.owlEditor.model.merge.MergeResult;
import self.research.ontology.owlEditor.model.merge.MergeStrategy;

class OntologyMergeServiceTest {

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

    private OntologyMergeService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        service = new OntologyMergeService(datasetService, importService, storageManager, metadataService, historyService);

        Path targetFile = tempDir.resolve("target.owl");
        writeOntologyWithClass(targetFile, "http://ex.org/Target");
        when(storageManager.findCurrentOntology("proj-1")).thenReturn(Optional.of(targetFile));
        when(metadataService.readMeta(anyString())).thenReturn(Optional.empty());
        doNothing().when(metadataService).writeMeta(anyString(), any());
        doNothing().when(importService).submitImport(anyString(), any());
    }

    private static void writeOntologyWithClass(Path path, String classIri) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.createOntology(IRI.create("http://ex.org/onto-" + path.getFileName()));
        manager.addAxiom(ontology, manager.getOWLDataFactory().getOWLDeclarationAxiom(
                manager.getOWLDataFactory().getOWLClass(IRI.create(classIri))));
        try (FileOutputStream fos = new FileOutputStream(path.toFile())) {
            manager.saveOntology(ontology, new RDFXMLDocumentFormat(), fos);
        }
    }

    @Test
    void mergeOntologiesRecordsASummaryHistoryEntryOnSuccess() throws Exception {
        Path sourceFile = tempDir.resolve("source.owl");
        writeOntologyWithClass(sourceFile, "http://ex.org/Source");

        MergeOptions options = new MergeOptions();
        options.setStrategy(MergeStrategy.SIMPLE_UNION);

        MergeResult result = service.mergeOntologies(
                "proj-1_source", sourceFile, "proj-1", null, null, options, "u1", "User One");

        assertTrue(result.isSuccess());
        verify(historyService).recordEdit(eq("proj-1"), eq("u1"), eq("User One"), eq("ontologyMerge"),
                isNull(), isNull(), isNull(), isNull(), org.mockito.ArgumentMatchers.contains("added"));
    }
}
