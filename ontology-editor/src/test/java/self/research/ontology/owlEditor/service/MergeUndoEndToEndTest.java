package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.merge.MergeOptions;
import self.research.ontology.owlEditor.model.merge.MergeStrategy;
import self.research.ontology.owlEditor.service.RollbackMutationPlanner.Plan;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MergeUndoEndToEndTest {

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

    private OntologyMergeService service;
    private Path targetFile;
    private Path sourceFile;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        service = new OntologyMergeService(datasetService, importService, storageManager, metadataService, historyService);
        targetFile = dir.resolve("target.owl");
        sourceFile = dir.resolve("source.owl");
        write(targetFile, "target", "A", "Alpha");
        write(sourceFile, "source", "A", "Alpha changed");
        when(storageManager.findCurrentOntology("proj")).thenReturn(Optional.of(targetFile));
        when(metadataService.readMeta(anyString())).thenReturn(Optional.empty());
        doNothing().when(metadataService).writeMeta(anyString(), any());
        when(importService.trySubmitImport(anyString(), any())).thenReturn(true);
    }

    private static void write(Path path, String name, String shared, String label) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(IRI.create(NS + name));
        OWLObjectProperty p = df.getOWLObjectProperty(IRI.create(NS + "p"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + shared))));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(df.getOWLClass(IRI.create(NS + name + "Only"))));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(p));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), IRI.create(NS + shared),
                df.getOWLLiteral(label)));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(df.getOWLClass(IRI.create(NS + name + "Only")),
                df.getOWLObjectSomeValuesFrom(p, df.getOWLClass(IRI.create(NS + shared)))));
        try (FileOutputStream out = new FileOutputStream(path.toFile())) {
            manager.saveOntology(ontology, new RDFXMLDocumentFormat(), out);
        }
    }

    private static Model modelOf(Path file) throws Exception {
        return Rio.parse(Files.newInputStream(file), "", RDFFormat.RDFXML);
    }

    private HistoryChange recordedMerge(MergeStrategy strategy) throws Exception {
        MergeOptions options = new MergeOptions();
        options.setStrategy(strategy);
        service.mergeOntologies("src", sourceFile, "proj", null, null, options, "u1", "User");
        ArgumentCaptor<ChangeOrigin> origin = ArgumentCaptor.forClass(ChangeOrigin.class);
        verify(historyService).recordEdit(eq("proj"), eq("u1"), eq("User"), eq("ontologyMerge"), anyString(),
                eq("Ontology merge"), any(), any(), anyString(), any(), any(), eq(false), origin.capture());
        assertTrue(origin.getValue().exact() != null, "the merge recorded no exact change");
        Map<String, Object> stamped = new HashMap<>();
        origin.getValue().exact().stamp(stamped, "e1");
        HistoryChange change = new HistoryChange();
        change.setId("m1");
        change.setOperationType("ontologyMerge");
        change.setEntityIRI(OntologyMergeService.MERGE_IRI_PREFIX + "x");
        change.setExactDeleted((String) stamped.get("exactDeleted"));
        change.setExactInserted((String) stamped.get("exactInserted"));
        return change;
    }

    private void applyAll(SailRepository repository, Plan plan) {
        try (RepositoryConnection connection = repository.getConnection()) {
            for (String update : plan.rawUpdates()) {
                try {
                    connection.prepareUpdate(update).execute();
                } catch (RuntimeException e) {
                    throw new IllegalStateException("FAILED UPDATE: " + update, e);
                }
            }
        }
    }

    private static Model contentOf(SailRepository repository) {
        Model content = new org.eclipse.rdf4j.model.impl.LinkedHashModel();
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.getStatements(null, null, null).forEach(content::add);
        }
        return content;
    }

    private void undoAndRedoRestoresTheGraph(MergeStrategy strategy) throws Exception {
        Model original = modelOf(targetFile);
        HistoryChange change = recordedMerge(strategy);
        Model merged = modelOf(targetFile);
        assertFalse(Models.isomorphic(original, merged));

        SailRepository repository = new SailRepository(new MemoryStore());
        repository.init();
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(merged);
        }
        RollbackMutationPlanner planner = new RollbackMutationPlanner();
        applyAll(repository, planner.planUndo(change, java.util.List.of()));
        assertTrue(Models.isomorphic(contentOf(repository), original), "undo did not return the original graph");
        applyAll(repository, planner.planRedo(change, java.util.List.of()));
        assertTrue(Models.isomorphic(contentOf(repository), merged), "redo did not bring the merge back");
        repository.shutDown();
    }

    @Test
    void aSimpleUnionMergeUndoesAndRedoesExactly() throws Exception {
        undoAndRedoRestoresTheGraph(MergeStrategy.SIMPLE_UNION);
    }

    @Test
    void aReplaceDuplicatesMergeUndoesAndRedoesExactly() throws Exception {
        undoAndRedoRestoresTheGraph(MergeStrategy.REPLACE_DUPLICATES);
    }

    @Test
    void aKeepBothMergeUndoesAndRedoesExactly() throws Exception {
        undoAndRedoRestoresTheGraph(MergeStrategy.KEEP_BOTH);
    }
}
