package self.research.ontology.owlEditor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import self.research.ontology.owlEditor.model.merge.MergeAnalysisResult;
import self.research.ontology.owlEditor.model.merge.MergeConflict;
import self.research.ontology.owlEditor.model.merge.MergeCopy;
import self.research.ontology.owlEditor.model.merge.MergeOptions;
import self.research.ontology.owlEditor.model.merge.MergeResult;
import self.research.ontology.owlEditor.model.merge.MergeStrategy;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

class KeepBothCopiesEverySharedEntityTest {

    private static final String NS = "http://ex.org/";
    private static final String SUFFIX = "_imported";

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
        write(targetFile, "target", "Diff target", "target note");
        write(sourceFile, "source", "Diff source", "source note");
        when(storageManager.findCurrentOntology("proj")).thenReturn(Optional.of(targetFile));
        when(metadataService.readMeta(anyString())).thenReturn(Optional.empty());
        doNothing().when(metadataService).writeMeta(anyString(), any());
        when(importService.trySubmitImport(anyString(), any())).thenReturn(true);
    }

    private static IRI iri(String local) {
        return IRI.create(NS + local);
    }

    private static void write(Path path, String name, String diffLabel, String vocabularyNote) throws Exception {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory df = manager.getOWLDataFactory();
        OWLOntology ontology = manager.createOntology(iri(name));
        for (String cls : new String[]{"Same", "Diff", name + "Only"}) {
            manager.addAxiom(ontology, df.getOWLDeclarationAxiom(df.getOWLClass(iri(cls))));
        }
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), iri("Same"), df.getOWLLiteral("Same label")));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSLabel(), iri("Diff"), df.getOWLLiteral(diffLabel)));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSIsDefinedBy(), iri("Same"), iri("ontology")));
        OWLDataProperty code = df.getOWLDataProperty(iri("hasCode"));
        manager.addAxiom(ontology, df.getOWLDeclarationAxiom(code));
        manager.addAxiom(ontology, df.getOWLDataPropertyRangeAxiom(code, df.getOWLDatatype(OWL2Datatype.XSD_STRING.getIRI())));
        manager.addAxiom(ontology, df.getOWLAnnotationAssertionAxiom(df.getRDFSComment(), df.getRDFSLabel().getIRI(),
                df.getOWLLiteral(vocabularyNote)));
        try (FileOutputStream out = new FileOutputStream(path.toFile())) {
            manager.saveOntology(ontology, new RDFXMLDocumentFormat(), out);
        }
    }

    private MergeResult mergeKeepingBoth() throws Exception {
        return mergeKeepingBoth(SUFFIX);
    }

    private MergeResult mergeKeepingBoth(String suffix) throws Exception {
        MergeOptions options = new MergeOptions();
        options.setStrategy(MergeStrategy.KEEP_BOTH);
        options.setRenameSuffix(suffix);
        return service.mergeOntologies("src", sourceFile, "proj", null, null, options, "u1", "User");
    }

    private Set<String> merged(java.util.function.Function<OWLOntology, Set<? extends org.semanticweb.owlapi.model.OWLEntity>> kind)
            throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(targetFile.toFile());
        return kind.apply(ontology).stream().map(e -> e.getIRI().toString()).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void anEntityThatIsTheSameInBothOntologiesStillGetsACopy() throws Exception {
        mergeKeepingBoth();

        Set<String> classes = merged(OWLOntology::getClassesInSignature);

        assertTrue(classes.contains(NS + "Same"));
        assertTrue(classes.contains(NS + "Same" + SUFFIX));
    }

    @Test
    void anEntityWithTheSameNameAndADifferentDefinitionIsKeptInBothVersions() throws Exception {
        mergeKeepingBoth();

        Set<String> classes = merged(OWLOntology::getClassesInSignature);

        assertTrue(classes.contains(NS + "Diff"));
        assertTrue(classes.contains(NS + "Diff" + SUFFIX));
    }

    @Test
    void anEntityThatExistsInOnlyOneOntologyIsKeptUnchanged() throws Exception {
        mergeKeepingBoth();

        Set<String> classes = merged(OWLOntology::getClassesInSignature);

        assertTrue(classes.contains(NS + "targetOnly"));
        assertTrue(classes.contains(NS + "sourceOnly"));
        assertFalse(classes.contains(NS + "sourceOnly" + SUFFIX));
        assertFalse(classes.contains(NS + "targetOnly" + SUFFIX));
    }

    @Test
    void standardVocabularyAlwaysKeepsItsOriginalName() throws Exception {
        mergeKeepingBoth();

        Set<String> annotationProperties = merged(OWLOntology::getAnnotationPropertiesInSignature);
        Set<String> datatypes = merged(OWLOntology::getDatatypesInSignature);

        assertTrue(annotationProperties.contains("http://www.w3.org/2000/01/rdf-schema#label"));
        assertTrue(annotationProperties.contains("http://www.w3.org/2000/01/rdf-schema#isDefinedBy"));
        assertTrue(datatypes.contains("http://www.w3.org/2001/XMLSchema#string"));
        assertTrue(annotationProperties.stream().noneMatch(iri -> iri.startsWith("http://www.w3.org/") && iri.endsWith(SUFFIX)));
        assertTrue(datatypes.stream().noneMatch(iri -> iri.endsWith(SUFFIX)));
        assertTrue(merged(OWLOntology::getDataPropertiesInSignature).contains(NS + "hasCode" + SUFFIX));
    }

    @Test
    void theWizardNeverListsAStandardWordAsAConflict() throws Exception {
        MergeAnalysisResult analysis = service.analyzeOntologies("src", sourceFile, "proj");

        assertTrue(analysis.getConflicts().stream().map(MergeConflict::getEntityIRI)
                .noneMatch(iri -> iri.startsWith("http://www.w3.org/")));
    }

    @Test
    void everyEntityInBothOntologiesIsCopiedNotOnlyTheConflictsTheWizardListed() throws Exception {
        MergeAnalysisResult analysis = service.analyzeOntologies("src", sourceFile, "proj");
        Set<String> listed = analysis.getConflicts().stream().map(MergeConflict::getEntityIRI)
                .collect(Collectors.toCollection(TreeSet::new));

        MergeResult result = mergeKeepingBoth();
        Set<String> copied = result.getCopies().stream().map(MergeCopy::originalIri)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(Set.of(NS + "Diff"), listed);
        assertEquals(Set.of(NS + "Same", NS + "Diff", NS + "hasCode"), copied);
        assertEquals(3, result.getEntitiesRenamed());
        assertEquals(3, result.getConflictsResolved());
    }

    @Test
    void aBlankSuffixFallsBackToTheDefaultSoBothVersionsAreStillKept() throws Exception {
        MergeResult result = mergeKeepingBoth(" ");

        assertTrue(result.getCopies().stream().allMatch(copy -> copy.newIri().equals(copy.originalIri() + SUFFIX)));
        assertTrue(merged(OWLOntology::getClassesInSignature).containsAll(Set.of(NS + "Same", NS + "Same" + SUFFIX)));
    }

    @Test
    void mergingAnIdenticalOntologyCopiesEveryEntityInIt() throws Exception {
        write(sourceFile, "target", "Diff target", "target note");

        MergeResult result = mergeKeepingBoth();

        assertEquals(4, result.getEntitiesRenamed());
        assertEquals(Set.of(NS + "Same" + SUFFIX, NS + "Diff" + SUFFIX, NS + "targetOnly" + SUFFIX),
                merged(OWLOntology::getClassesInSignature).stream().filter(iri -> iri.endsWith(SUFFIX))
                        .collect(Collectors.toSet()));
    }
}
