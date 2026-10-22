package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
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
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import self.research.ontology.owlEditor.model.HistoryChange;
import self.research.ontology.owlEditor.model.merge.MergeOptions;
import self.research.ontology.owlEditor.model.merge.MergeStrategy;
import self.research.ontology.owlEditor.service.delta.DeltaSparql;
import self.research.ontology.owlEditor.service.delta.ExactDelta;
import self.research.ontology.owlEditor.service.delta.ExactReversal;
import self.research.ontology.owlEditor.util.OWLFormatConverter;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KeepBothMergeRoundTripTest {

    private static final String NS = "http://ex.org/";
    private static final String ONT = "http://ex.org/ontology";
    private static final String GRAPH = "urn:test:project";
    private static final List<String> STANDARD = List.of("http://www.w3.org/1999/02/22-rdf-syntax-ns#",
            "http://www.w3.org/2000/01/rdf-schema#", "http://www.w3.org/2002/07/owl#", "http://www.w3.org/2001/XMLSchema#");

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
        write(targetFile, "target", "Alpha target");
        write(sourceFile, "source", "Alpha source");
        when(storageManager.findCurrentOntology("proj")).thenReturn(Optional.of(targetFile));
        when(metadataService.readMeta(anyString())).thenReturn(Optional.empty());
        doNothing().when(metadataService).writeMeta(anyString(), any());
        when(importService.trySubmitImport(anyString(), any())).thenReturn(true);
    }

    private static void write(Path path, String name, String alphaLabel) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(org.semanticweb.owlapi.model.IRI.create(NS + name));
        OWLClass alpha = df.getOWLClass(org.semanticweb.owlapi.model.IRI.create(NS + "Alpha"));
        OWLClass beta = df.getOWLClass(org.semanticweb.owlapi.model.IRI.create(NS + "Beta"));
        OWLClass own = df.getOWLClass(org.semanticweb.owlapi.model.IRI.create(NS + name + "Only"));
        for (OWLClass cls : List.of(alpha, beta, own)) {
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(cls));
            manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSIsDefinedBy(), cls.getIRI(),
                    org.semanticweb.owlapi.model.IRI.create(ONT)));
        }
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), alpha.getIRI(),
                df.getOWLLiteral(alphaLabel)));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), beta.getIRI(),
                df.getOWLLiteral("Beta same")));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSComment(), own.getIRI(),
                df.getOWLLiteral("typed text", OWL2Datatype.XSD_STRING)));
        manager.addAxiom(ontology, df.getOWLSubClassOfAxiom(own, alpha));
        try (FileOutputStream out = new FileOutputStream(path.toFile())) {
            manager.saveOntology(ontology, new RDFXMLDocumentFormat(), out);
        }
    }

    private HistoryChange mergeKeepingBoth() throws Exception {
        MergeOptions options = new MergeOptions();
        options.setStrategy(MergeStrategy.KEEP_BOTH);
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

    private Model importedAsTheServerDoes() throws Exception {
        Path prepared = dir.resolve("prepared.owl");
        Files.copy(targetFile, prepared, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        OWLFormatConverter.sanitizeFileOnDisk(prepared);
        return Rio.parse(Files.newInputStream(prepared), "", RDFFormat.RDFXML);
    }

    private static boolean usesStandardWordWithSuffix(Model model) {
        return model.stream().anyMatch(st -> endsWithSuffix(st.getSubject().stringValue())
                || endsWithSuffix(st.getPredicate().stringValue()) || endsWithSuffix(st.getObject().stringValue())
                || (st.getObject() instanceof org.eclipse.rdf4j.model.Literal literal
                && endsWithSuffix(literal.getDatatype().stringValue())));
    }

    private static boolean endsWithSuffix(String iri) {
        return iri.endsWith("_imported") && STANDARD.stream().anyMatch(iri::startsWith);
    }

    @Test
    void theMergedFileKeepsEveryStandardWordUnrenamed() throws Exception {
        mergeKeepingBoth();

        Model saved = Rio.parse(Files.newInputStream(targetFile), "", RDFFormat.RDFXML);

        assertFalse(usesStandardWordWithSuffix(saved), "a standard rdf, rdfs, owl or xsd word was renamed");
    }

    @Test
    void whatTheMergeRecordedIsStillInTheStoreAfterTheServerImportsTheFile() throws Exception {
        HistoryChange change = mergeKeepingBoth();
        Model imported = importedAsTheServerDoes();
        SailRepository repository = new SailRepository(new MemoryStore());
        repository.init();
        IRI graph = SimpleValueFactory.getInstance().createIRI(GRAPH);
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(imported, graph);
            ExactDelta delta = ExactReversal.deltaOf(change).orElseThrow();
            for (String query : DeltaSparql.holds(delta, true, GRAPH)) {
                assertTrue(connection.prepareBooleanQuery(query).evaluate(),
                        "undo would be refused: the store does not hold what the merge recorded");
            }
        } finally {
            repository.shutDown();
        }
        assertEquals(false, imported.isEmpty());
    }
}
